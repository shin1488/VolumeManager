import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
            implementation(compose.materialIconsExtended)
            implementation("net.java.dev.jna:jna:5.16.0")
            implementation("net.java.dev.jna:jna-platform:5.16.0")
        }
    }
}


compose.desktop {
    application {
        mainClass = "com.shin.volumemanager.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "VolumeManager"
            packageVersion = "1.0.0"
            description = "VolumeManager"
            vendor = "Shin"

            windows {
                // Install under C:\Program Files\VolumeManager instead of
                // the default C:\Program Files\<packageName>\<packageName>
                // nesting, and give Start Menu / Add-Remove Programs the
                // friendly "VolumeManager" name rather than the package id.
                dirChooser = true
                perUserInstall = false
                menuGroup = "VolumeManager"
                // Stable UpgradeCode — lets future MSIs upgrade this one
                // in place instead of installing side-by-side.
                upgradeUuid = "8F2A1B4E-5C3D-4E6F-9A7B-1C2D3E4F5A6B"
                shortcut = true
            }

            // jlink strips the runtime down to what `suggestRuntimeModules`
            // detects via bytecode scanning, but JNA loads native code
            // reflectively so the scanner misses `jdk.unsupported`
            // (sun.misc.Unsafe) and `java.instrument`. Without them the
            // packaged app launches but every COM call silently fails —
            // which is why audio sessions never showed up in the MSI build
            // while the IntelliJ run (full JDK) worked fine.
            modules("java.instrument", "jdk.unsupported")
        }

        // ProGuard runs in the release build. Its defaults rename/strip
        // com.sun.jna.** and our COM vtable wrappers — everything uses
        // reflection + native-method linkage, which the bytecode shrinker
        // can't see. Point it at a custom rules file that pins those
        // packages so the packaged MSI can still talk to Core Audio.
        buildTypes.release.proguard {
            configurationFiles.from("proguard-rules.pro")
        }
    }
}
