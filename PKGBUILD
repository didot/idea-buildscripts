# Maintainer: Lukas Jirkovsky <l.jirkovsky@gmail.com>
# Maintainer: Levente Polyak <anthraxx[at]archlinux[dot]org>
# Maintainer: Maxime Gauduin <alucryd@archlinux.org>
# Maintainer: Orhun Parmaksız <orhun@archlinux.org>

pkgname=intellij-idea-community-edition
pkgver=2022.2.3
_build=223.7571.182
_jdk=17.0.5-amzn
_jrever=17
_jdkver=17
pkgrel=2
epoch=4
pkgdesc='IDE for Java, Groovy and other programming languages with advanced refactoring features'
url='https://www.jetbrains.com/idea/'
arch=('x86_64')
license=('Apache')
backup=('usr/share/idea/bin/idea64.vmoptions')
depends=('giflib' "java-runtime=${_jrever}" 'python' 'sh' 'ttf-font' 'libdbusmenu-glib' 'fontconfig' 'hicolor-icon-theme')
makedepends=('ant' 'git' "java-environment-openjdk=${_jdkver}" maven)
optdepends=(
  'lldb: lldb frontend integration'
)
source=("git+https://github.com/JetBrains/intellij-community.git#tag=idea/${_build}"
        idea-android::"git://git.jetbrains.org/idea/android.git#tag=idea/${_build}"
        idea-adt-tools-base::"git://git.jetbrains.org/idea/adt-tools-base.git#commit=17e9c8b666cac0b974b1efc5e1e4c33404f72904"
        idea.desktop
        idea.sh
        # The class src/com/intellij/openapi/projectRoots/ex/JavaSdkUtil.java:56 (git commit 0ea5972cdad569407078fb27070c80e2b9235c53)
        # assumes the user's maven repo is at {$HOME}/.m2/repository and it contains junit-3.8.1.jar
        https://repo1.maven.org/maven2/junit/junit/3.8.1/junit-3.8.1.jar)
noextract=('junit-3.8.1.jar')
sha256sums=('SKIP'
            'SKIP'
            'SKIP'
            '049c4326b6b784da0c698cf62262b591b20abb52e0dcf869f869c0c655f3ce93'
            'd7e4a325fccd48b8c8b0a6234df337b58364e648bb9b849e85ca38a059468e71'
            'b58e459509e190bed737f3592bc1950485322846cf10e78ded1d065153012d70')

prepare() {
  export MAVEN_REPOSITORY=${srcdir}/.m2/repository
  mvn install:install-file \
    -Dfile=junit-3.8.1.jar \
    -DgroupId=junit \
    -DartifactId=junit \
    -Dversion=3.8.1 \
    -Dpackaging=jar \
    -DgeneratePom=true

  echo "${_build}" > intellij-community/build.txt
}

build() {
  cd intellij-community
  
  export JAVA_HOME="/Users/bdidot/.sdkman/candidates/java/${_jdk}"
  export PATH="/Users/bdidot/.sdkman/candidates/java/${_jdk}/bin:$PATH"
#  export MAVEN_REPOSITORY=${srcdir}/.m2/repository
  
  ./installers.cmd \
    -Dintellij.build.use.compiled.classes=false \
    -Dintellij.build.target.os=mac \
    -Dintellij.build.dmg.with.bundled.jre=true \
    -Dintellij.build.dmg.without.bundled.jre=false \
    -Dintellij.build.skip.build.steps=mac_dmg,mac_sit
#  tar -xf out/idea-ce/artifacts/ideaIC-${_build}-no-jbr.tar.gz -C "${srcdir}"
}

package() {
  cd idea-IC-${_build}

  install -dm 755 "${pkgdir}"/usr/share/{licenses,pixmaps,idea,icons/hicolor/scalable/apps}
  cp -dr --no-preserve='ownership' bin lib plugins "${pkgdir}"/usr/share/idea/
  cp -dr --no-preserve='ownership' license "${pkgdir}"/usr/share/licenses/idea
  ln -s /usr/share/idea/bin/idea.png "${pkgdir}"/usr/share/pixmaps/
  ln -s /usr/share/idea/bin/idea.svg "${pkgdir}"/usr/share/icons/hicolor/scalable/apps/
  install -Dm 644 ../idea.desktop -t "${pkgdir}"/usr/share/applications/
  install -Dm 755 ../idea.sh "${pkgdir}"/usr/bin/idea
  install -Dm 644 build.txt -t "${pkgdir}"/usr/share/idea
}

# vim: ts=2 sw=2 et:
