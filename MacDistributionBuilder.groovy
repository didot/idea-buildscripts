// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.intellij.build.impl

import com.intellij.diagnostic.telemetry.TraceKt
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.util.SystemProperties
import groovy.transform.CompileStatic
import io.opentelemetry.api.trace.Span
import kotlin.Pair
import kotlin.Unit
import kotlin.jvm.functions.Function0
import org.jetbrains.annotations.NotNull
import org.jetbrains.annotations.Nullable
import org.jetbrains.intellij.build.*
import org.jetbrains.intellij.build.impl.productInfo.ProductInfoGeneratorKt
import org.jetbrains.intellij.build.impl.productInfo.ProductInfoLaunchData
import org.jetbrains.intellij.build.impl.productInfo.ProductInfoValidatorKt
import org.jetbrains.intellij.build.io.FileKt
import org.jetbrains.intellij.build.tasks.MacKt

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.concurrent.ForkJoinTask
import java.util.function.BiConsumer
import java.util.zip.Deflater

import static org.jetbrains.intellij.build.TraceManager.spanBuilder
import static org.jetbrains.intellij.build.impl.BuildTasksImplKt.updateExecutablePermissions

@CompileStatic
final class MacDistributionBuilder implements OsSpecificDistributionBuilder {
  private final MacDistributionCustomizer customizer
  private final Path ideaProperties
  @SuppressWarnings('SpellCheckingInspection')
  private final String targetIcnsFileName
  private final BuildContext context

  MacDistributionBuilder(BuildContext context, MacDistributionCustomizer customizer, Path ideaProperties) {
    this.context = context
    this.ideaProperties = ideaProperties
    this.customizer = customizer
    targetIcnsFileName = "${context.productProperties.baseFileName}.icns"
  }

  @Override
  OsFamily getTargetOs() {
    return OsFamily.MACOS
  }

  String getDocTypes() {
    List<String> associations = new ArrayList<>()

    if (customizer.associateIpr) {
      String association = """<dict>
        <key>CFBundleTypeExtensions</key>
        <array>
          <string>ipr</string>
        </array>
        <key>CFBundleTypeIconFile</key>
        <string>${targetIcnsFileName}</string>
        <key>CFBundleTypeName</key>
        <string>${context.applicationInfo.productName} Project File</string>
        <key>CFBundleTypeRole</key>
        <string>Editor</string>
      </dict>"""
      associations.add(association)
    }

    for (FileAssociation fileAssociation : customizer.fileAssociations) {
      String iconPath = fileAssociation.iconPath
      String association = """<dict>
        <key>CFBundleTypeExtensions</key>
        <array>
          <string>${fileAssociation.extension}</string>
        </array>
        <key>CFBundleTypeIconFile</key>
        <string>${iconPath.isEmpty() ? targetIcnsFileName : new File(iconPath).name}</string>        
        <key>CFBundleTypeRole</key>
        <string>Editor</string>
      </dict>"""
      associations.add(association)
    }

    return String.join("\n      ", associations) + customizer.additionalDocTypes
  }

  @Override
  void copyFilesForOsDistribution(@NotNull Path macDistDir, JvmArchitecture arch = null) {
    doCopyExtraFiles(macDistDir, arch, true)
  }

  private void doCopyExtraFiles(Path macDistDir, JvmArchitecture arch, boolean copyDistFiles) {
    //noinspection SpellCheckingInspection
    List<String> platformProperties = new ArrayList<String>(Arrays.asList(
      "\n#---------------------------------------------------------------------",
      "# macOS-specific system properties",
      "#---------------------------------------------------------------------",
      "com.apple.mrj.application.live-resize=false",
      "apple.laf.useScreenMenuBar=true",
      "jbScreenMenuBar.enabled=true",
      "apple.awt.fileDialogForDirectories=true",
      "apple.awt.graphics.UseQuartz=true",
      "apple.awt.fullscreencapturealldisplays=false"
    ))
    customizer.getCustomIdeaProperties(context.applicationInfo).forEach(new BiConsumer<String, String>() {
      @Override
      void accept(String k, String v) {
        platformProperties.add(k + '=' + v)
      }
    })

    layoutMacApp(ideaProperties, platformProperties, getDocTypes(), macDistDir, context)

    DistUtilKt.unpackPty4jNative(context, macDistDir, "darwin")

    DistUtilKt.generateBuildTxt(context, macDistDir.resolve("Resources"))
    if (copyDistFiles) {
      DistUtilKt.copyDistFiles(context, macDistDir)
    }

    customizer.copyAdditionalFiles(context, macDistDir.toString())
    if (arch != null) {
      customizer.copyAdditionalFiles(context, macDistDir, arch)
    }

    UnixScriptBuilder.generateScripts(context, Collections.<String>emptyList(), macDistDir.resolve("bin"), OsFamily.MACOS)
  }

  @Override
  void buildArtifacts(@NotNull Path osAndArchSpecificDistPath, @NotNull JvmArchitecture arch) {
    doCopyExtraFiles(osAndArchSpecificDistPath, arch, false)
    BuildContextKt.executeStep(context, spanBuilder("build macOS artifacts")
                               .setAttribute("arch", arch.name()), BuildOptions.MAC_ARTIFACTS_STEP, new Function0<Unit>() {
      @Override
      Unit invoke() {
        doBuildArtifacts(osAndArchSpecificDistPath, arch)
        return Unit.INSTANCE
      }
    })
  }

  private void doBuildArtifacts(Path osAndArchSpecificDistPath, JvmArchitecture arch) {
    String baseName = context.productProperties.getBaseArtifactName(context.applicationInfo, context.buildNumber)
    boolean publishArchive = context.proprietaryBuildTools.macHostProperties?.host == null && !SystemInfoRt.isMac

    List<String> binariesToSign = customizer.getBinariesToSign(context, arch)
    if (!binariesToSign.isEmpty()) {
      BuildContextKt.executeStep(context, spanBuilder("sign binaries for macOS distribution")
                            .setAttribute("arch", arch.name()), BuildOptions.MAC_SIGN_STEP, new Function0<Unit>() {
        @Override
        Unit invoke() {
          context.signFiles(binariesToSign.collect { osAndArchSpecificDistPath.resolve(it) }, Map.of(
            "mac_codesign_options", "runtime",
            "mac_codesign_force", "true",
            "mac_codesign_deep", "true",
            ))
          return Unit.INSTANCE
        }
      })
    }

    Path macZip = ((publishArchive || customizer.publishArchive) ? context.paths.artifactDir : context.paths.tempDir)
      .resolve(baseName + ".mac.${arch.name()}.zip")
    String zipRoot = getZipRoot(context, customizer)
    def executableFilePatterns = getExecutableFilePatterns(customizer)
    updateExecutablePermissions(context.paths.distAllDir, executableFilePatterns)
    updateExecutablePermissions(osAndArchSpecificDistPath, executableFilePatterns)
    MacKt.buildMacZip(
      macZip,
      zipRoot,
      generateProductJson(context.builtinModule, context, null),
      context.paths.distAllDir,
      osAndArchSpecificDistPath,
      context.getDistFiles(),
      executableFilePatterns,
      publishArchive ? Deflater.DEFAULT_COMPRESSION : Deflater.BEST_SPEED
    )
    ProductInfoValidatorKt.checkInArchive(context, macZip, "$zipRoot/Resources")

    if (publishArchive) {
      Span.current().addEvent("skip DMG artifact producing because a macOS build agent isn't configured")
      context.notifyArtifactBuilt(macZip)
    }
    else {
      buildAndSignDmgFromZip(macZip, arch, context.builtinModule).invoke()
    }
  }

  ForkJoinTask<?> buildAndSignDmgFromZip(Path macZip, JvmArchitecture arch, @Nullable BuiltinModulesFileData builtinModule) {
    createBuildForArchTask(builtinModule, arch, macZip, customizer, context)
  }

  private static ForkJoinTask<?> createBuildForArchTask(@Nullable BuiltinModulesFileData builtinModule,
                                                        JvmArchitecture arch,
                                                        Path macZip,
                                                        MacDistributionCustomizer customizer,
                                                        BuildContext context) {
    return TraceKt.createTask(spanBuilder("build macOS artifacts for specific arch").setAttribute("arch", arch.name()), {
      boolean notarize = SystemProperties.getBooleanProperty("intellij.build.mac.notarize", true)
      println("MacDistributionBUilder#createBuildForArchTask context = ${context}")
      println("MacDistributionBUilder#createBuildForArchTask context.bundledRuntime = ${context.bundledRuntime}")
      List<ForkJoinTask<?>> allBuilds = buildForArch(builtinModule, arch, context.bundledRuntime, macZip, notarize, customizer, context)
      allBuilds = allBuilds.findAll { it != null }
      ForkJoinTask.invokeAll(allBuilds)
      Files.deleteIfExists(macZip)
    })
  }

  private static List<ForkJoinTask<?>> buildForArch(@Nullable BuiltinModulesFileData builtinModule,
                                                    JvmArchitecture arch,
                                                    BundledRuntime jreManager,
                                                    Path macZip,
                                                    boolean notarize,
                                                    MacDistributionCustomizer customizer,
                                                    BuildContext context) {
    List<ForkJoinTask<?>> tasks = new ArrayList<ForkJoinTask<?>>()
    String suffix = arch == JvmArchitecture.x64 ? "" : "-${arch.fileSuffix}"
    String archStr = arch.name()
    // with JRE
    if (context.options.buildDmgWithBundledJre) {
      tasks.add(BuildHelperKt.createSkippableTask(
        spanBuilder("build DMG with JRE").setAttribute("arch", archStr),
        "${BuildOptions.MAC_ARTIFACTS_STEP}_jre_$archStr",
        context,
        new Function0<Unit>() {
          @Override
          Unit invoke() {
            Path jreArchive = jreManager.findArchive(BundledRuntimeImpl.getProductPrefix(context), OsFamily.MACOS, arch)
            MacDmgBuilder.signAndBuildDmg(builtinModule, context, customizer, context.proprietaryBuildTools.macHostProperties, macZip,
                                          jreArchive, suffix, arch, notarize)
            return null
          }
        }))
    }

    // without JRE
    if (context.options.buildDmgWithoutBundledJre) {
      tasks.add(BuildHelperKt.createSkippableTask(
        spanBuilder("build DMG without JRE").setAttribute("arch", archStr),
        "${BuildOptions.MAC_ARTIFACTS_STEP}_no_jre_$archStr",
        context, new Function0<Unit>() {
        @Override
        Unit invoke() {
          MacDmgBuilder.signAndBuildDmg(builtinModule, context, customizer, context.proprietaryBuildTools.macHostProperties, macZip,
                                        null, "-no-jdk$suffix", arch, notarize)
          return null
        }
      }))
    }
    return tasks
  }

  private void layoutMacApp(Path ideaPropertiesFile,
                            List<String> platformProperties,
                            String docTypes,
                            Path macDistDir,
                            BuildContext context) {
    MacDistributionCustomizer macCustomizer = customizer
    BuildHelperKt.copyDirWithFileFilter(context.paths.communityHomeDir.resolve("bin/mac"),
                                      macDistDir.resolve("bin"),
                                      customizer.binFilesFilter)
    FileKt.copyDir(context.paths.communityHomeDir.resolve("platform/build-scripts/resources/mac/Contents"), macDistDir)

    String executable = context.productProperties.baseFileName
    Files.move(macDistDir.resolve("MacOS/executable"), macDistDir.resolve("MacOS/$executable"))

    //noinspection SpellCheckingInspection
    Path icnsPath = Path.of((context.applicationInfo.isEAP() ? customizer.icnsPathForEAP : null) ?: customizer.icnsPath)
    Path resourcesDistDir = macDistDir.resolve("Resources")
    FileKt.copyFile(icnsPath, resourcesDistDir.resolve(targetIcnsFileName))

    for (FileAssociation fileAssociation in customizer.fileAssociations) {
      if (!fileAssociation.iconPath.empty) {
        Path source = Path.of(fileAssociation.iconPath)
        Path dest = resourcesDistDir.resolve(source.fileName)
        Files.deleteIfExists(dest)
        FileKt.copyFile(source, dest)
      }
    }

    String fullName = context.applicationInfo.productName

    //todo[nik] improve
    String minor = context.applicationInfo.minorVersion
    boolean isNotRelease = context.applicationInfo.isEAP() && !minor.contains("RC") && !minor.contains("Beta")
    String version = isNotRelease ? "EAP $context.fullBuildNumber" : "${context.applicationInfo.majorVersion}.${minor}"
    String EAP = isNotRelease ? "-EAP" : ""

    List<String> properties = Files.readAllLines(ideaPropertiesFile)
    properties.addAll(platformProperties)
    Files.write(macDistDir.resolve("bin/idea.properties"), properties)

    String bootClassPath = String.join(":", context.getXBootClassPathJarNames().collect { "\$APP_PACKAGE/Contents/lib/$it" })
    String classPath = String.join(":", context.bootClassPathJarNames.collect { "\$APP_PACKAGE/Contents/lib/$it" })

    List<String> fileVmOptions = VmOptionsGenerator.computeVmOptions(context.applicationInfo.isEAP(), context.productProperties)
    List<String> additionalJvmArgs = context.getAdditionalJvmArguments(OsFamily.MACOS)
    if (!bootClassPath.isEmpty()) {
      additionalJvmArgs = new ArrayList<>(additionalJvmArgs)
      //noinspection SpellCheckingInspection
      additionalJvmArgs.add("-Xbootclasspath/a:" + bootClassPath)
    }
    List<List<String>> propsAndOpts = additionalJvmArgs.split { it.startsWith('-D') }
    List<String> launcherProperties = propsAndOpts[0], launcherVmOptions = propsAndOpts[1]

    fileVmOptions.add("-XX:ErrorFile=\$USER_HOME/java_error_in_${executable}_%p.log".toString())
    fileVmOptions.add("-XX:HeapDumpPath=\$USER_HOME/java_error_in_${executable}.hprof".toString())
    VmOptionsGenerator.writeVmOptions(macDistDir.resolve("bin/${executable}.vmoptions"), fileVmOptions, "\n")

    String vmOptionsXml = optionsToXml(launcherVmOptions)
    String vmPropertiesXml = propertiesToXml(launcherProperties, ['idea.executable': context.productProperties.baseFileName])

    String archString = '<key>LSArchitecturePriority</key>\n    <array>\n'
    for (String it in macCustomizer.architectures) {
      archString += '      <string>' + it + '</string>\n'
    }
    archString += '    </array>'

    List<String> urlSchemes = macCustomizer.urlSchemes
    String urlSchemesString = ""
    if (urlSchemes.size() > 0) {
      urlSchemesString += '''<key>CFBundleURLTypes</key>
    <array>
      <dict>
        <key>CFBundleTypeRole</key>
        <string>Editor</string>
        <key>CFBundleURLName</key>
        <string>Stacktrace</string>
        <key>CFBundleURLSchemes</key>
        <array>
'''
      urlSchemes.each {urlSchemesString += '          <string>' + it + '</string>\n' }
      urlSchemesString += '''\
        </array>
      </dict>
    </array>'''
    }
    String todayYear = LocalDate.now().year.toString()
    //noinspection SpellCheckingInspection
    FileKt.substituteTemplatePlaceholders(
      macDistDir.resolve("Info.plist"),
      macDistDir.resolve("Info.plist"),
      "@@",
      [
        new Pair<String, String>("build", context.fullBuildNumber),
        new Pair<String, String>("doc_types", docTypes ?: ""),
        new Pair<String, String>("executable", executable),
        new Pair<String, String>("icns", targetIcnsFileName),
        new Pair<String, String>("bundle_name", fullName),
        new Pair<String, String>("product_state", EAP),
        new Pair<String, String>("bundle_identifier", macCustomizer.bundleIdentifier),
        new Pair<String, String>("year", todayYear),
        new Pair<String, String>("version", version),
        new Pair<String, String>("vm_options", vmOptionsXml),
        new Pair<String, String>("vm_properties", vmPropertiesXml),
        new Pair<String, String>("class_path", classPath),
        new Pair<String, String>("url_schemes", urlSchemesString),
        new Pair<String, String>("architectures", archString),
        new Pair<String, String>("min_osx", macCustomizer.minOSXVersion),
      ]
    )

    Path distBinDir = macDistDir.resolve("bin")
    Files.createDirectories(distBinDir)

    Path sourceScriptDir = context.paths.communityHomeDir.resolve("platform/build-scripts/resources/mac/scripts")
    Files.newDirectoryStream(sourceScriptDir).withCloseable {stream ->
      String inspectCommandName = context.productProperties.inspectCommandName
      for (Path file : stream) {
        if (file.toString().endsWith(".sh")) {
          String fileName = file.fileName.toString()
          if (fileName == "inspect.sh" && inspectCommandName != "inspect") {
            fileName = "${inspectCommandName}.sh"
          }

          Path sourceFileLf = Files.createTempFile(context.paths.tempDir, file.fileName.toString(), "")
          try {
            // Until CR (\r) will be removed from the repository checkout, we need to filter it out from Unix-style scripts
            // https://youtrack.jetbrains.com/issue/IJI-526/Force-git-to-use-LF-line-endings-in-working-copy-of-via-gitattri
            Files.writeString(sourceFileLf, Files.readString(file).replace("\r", ""))

            Path target = distBinDir.resolve(fileName)
            FileKt.substituteTemplatePlaceholders(
              sourceFileLf,
              target,
              "@@",
              [
                new Pair<String, String>("product_full", fullName),
                new Pair<String, String>("script_name", executable),
                new Pair<String, String>("inspectCommandName", inspectCommandName),
              ],
              false,
            )
          }
          finally {
            Files.delete(sourceFileLf)
          }
        }
      }
    }
  }

  @Override
  List<String> generateExecutableFilesPatterns(boolean includeJre) {
    return getExecutableFilePatterns(customizer)
  }

  private static List<String> getExecutableFilePatterns(MacDistributionCustomizer customizer) {
    //noinspection SpellCheckingInspection
    return [
             "bin/*.sh",
             "bin/*.py",
             "bin/fsnotifier",
             "bin/printenv",
             "bin/restarter",
             "bin/repair",
             "MacOS/*"
           ] + customizer.extraExecutables
  }

  static String getZipRoot(BuildContext buildContext, MacDistributionCustomizer customizer) {
    return "${customizer.getRootDirectoryName(buildContext.applicationInfo, buildContext.buildNumber)}/Contents"
  }

  static String generateProductJson(@Nullable BuiltinModulesFileData builtinModule, BuildContext context, String javaExecutablePath) {
    String executable = context.productProperties.baseFileName
    return ProductInfoGeneratorKt.generateMultiPlatformProductJson(
      "../bin",
      builtinModule,
      List.of(
        new ProductInfoLaunchData(
          OsFamily.MACOS.osName,
          "../MacOS/${executable}",
          javaExecutablePath,
          "../bin/${executable}.vmoptions",
          null,
        )
      ), context
    )
  }

  private static String optionsToXml(List<String> options) {
    StringBuilder buff = new StringBuilder()
    for (String it in options) {
      buff.append('        <string>').append(it).append('</string>\n')
    }
    return buff.toString().trim()
  }

  private static String propertiesToXml(List<String> properties, Map<String, String> moreProperties) {
    StringBuilder buff = new StringBuilder()
    for (String it in properties) {
      int p = it.indexOf('=')
      buff.append('        <key>').append(it.substring(2, p)).append('</key>\n')
      buff.append('        <string>').append(it.substring(p + 1)).append('</string>\n')
    }
    moreProperties.each { key, value ->
      buff.append('        <key>').append(key).append('</key>\n')
      buff.append('        <string>').append(value).append('</string>\n')
    }
    return buff.toString().trim()
  }

  @Override
  List<String> getArtifactNames(@NotNull BuildContext context) {
    return List.of()
  }
}
