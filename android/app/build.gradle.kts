import com.android.build.api.dsl.ApplicationExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.time.LocalDateTime
import java.time.Month
import java.time.temporal.ChronoUnit

plugins {
	alias(libs.plugins.android.application)
}

extensions.configure<ApplicationExtension> {
	namespace = "su.xash.diffusion"
	ndkVersion = "30.0.16248370"
	compileSdk = 36

	defaultConfig {
		applicationId = "su.xash.diffusion"
		versionName = "1.0-" + getGitHash()
		versionCode = getBuildNum()
		minSdk = 21
		targetSdk = 36

		buildConfigField("String", "GIT_HASH", "\"${getGitHash()}\"")
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_11
		targetCompatibility = JavaVersion.VERSION_11
	}

	kotlin {
		compilerOptions {
			jvmTarget = JvmTarget.JVM_11
		}
	}

	buildFeatures {
		viewBinding = true
		buildConfig = true
	}

	signingConfigs {
		create("androidDebugKey") {
			storeFile = File(projectDir.parentFile, "debug.keystore")
			storePassword = "android"
			keyAlias = "androiddebugkey"
			keyPassword = "android"
		}
	}

	lint {
		abortOnError = false
	}

	packaging {
		jniLibs {
			keepDebugSymbols.add("**/*.so")
			useLegacyPackaging = true
		}
	}

	sourceSets {
		getByName("main") {
			assets.directories.add("../../3rdparty/extras/xash-extras")
		}
	}

	buildTypes {
		debug {
			isMinifyEnabled = false
			isShrinkResources = false
			isDebuggable = true
			proguardFiles(
				getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
			)
		}

		release {
			isMinifyEnabled = true
			isShrinkResources = true
			proguardFiles(
				getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
			)
			signingConfig = signingConfigs.getByName("androidDebugKey")
		}

		register("continuous") {
			initWith(getByName("release"))
			signingConfig = signingConfigs.getByName("androidDebugKey")
		}
	}
}

dependencies {
	implementation(libs.material)

	implementation(libs.appcompat)
	implementation(libs.navigation.runtime.ktx)
	implementation(libs.navigation.fragment.ktx)
	implementation(libs.navigation.ui.ktx)
	implementation(libs.preference.ktx)
	implementation(libs.swiperefreshlayout)

	implementation(libs.acra.http)
	implementation(libs.xz)
}

tasks.register("fetchXashExtras") {
	val extrasDir = File(projectDir.parentFile.parentFile, "3rdparty/extras/xash-extras")
	outputs.dir(extrasDir)
	doLast {
		if (!extrasDir.exists() || !File(extrasDir, "gamecontrollerdb.txt").exists()) {
			println("Fetching xash-extras dynamically...")
			extrasDir.parentFile.mkdirs()
			val process = ProcessBuilder("git", "clone", "--depth", "1", "https://github.com/FWGS/xash-extras.git", extrasDir.absolutePath)
				.inheritIO()
				.start()
			val exitCode = process.waitFor()
			if (exitCode != 0) {
				throw GradleException("Failed to clone xash-extras: exit code $exitCode")
			}
		}
	}
}

tasks.named("preBuild") {
	dependsOn("fetchXashExtras")
}

fun getBuildNum(): Int {
	val now = LocalDateTime.now()
	val releaseDate = LocalDateTime.of(2015, Month.APRIL, 1, 0, 0, 0)
	val qBuildNum = releaseDate.until(now, ChronoUnit.DAYS)
	val minuteOfDay = now.hour * 60 + now.minute
	return (qBuildNum * 10000 + minuteOfDay).toInt()
}

fun getGitHash(): String {
	return try {
		val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD").directory(project.rootDir)
			.redirectErrorStream(true).start()
		process.inputStream.bufferedReader().readText().trim()
	} catch (e: Exception) {
		"unknown"
	}
}
