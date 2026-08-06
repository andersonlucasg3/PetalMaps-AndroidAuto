group = "dev.petalaa"

patches {
    about {
        name = "PetalMaps Android Auto"
        description = "Patches to inject Android Auto support into Petal Maps (com.huawei.maps.app)"
        source = "https://github.com/petalaa/PetalMaps-AndroidAuto"
        author = "petalaa"
        contact = "petalaa@example.com"
        website = "https://github.com/petalaa/PetalMaps-AndroidAuto"
        license = "GNU General Public License v3.0"
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs = listOf("-Xcontext-parameters")
    }
}

dependencies {
    implementation(libs.morphe.patches.library)
}
