import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins { id("com.android.library"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.serialization") }
android { namespace = "ai.kuber.core.execution"; compileSdk = 35; defaultConfig { minSdk = 26 }; compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 } }
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
dependencies { api(project(":core-model")); api(project(":core-broker")); api(project(":core-paper")); api(project(":core-risk")); testImplementation("junit:junit:4.13.2") }
