// Convention plugins shared by every module, so module build files only name what is specific to them.
plugins { `kotlin-dsl` }

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.serialization.plugin)
    implementation(libs.compose.compiler.plugin)
    implementation(libs.compose.gradle.plugin)
}
