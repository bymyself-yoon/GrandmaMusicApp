import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ── 업데이트 설정 ───────────────────────────────────────────
// 새 버전을 낼 때마다 versionCode를 1씩 올려줘! (안 올리면 폰이 업데이트로 인식 안 함)
val appVersionCode = 2
val appVersionName = "1.1.0"

// 앱이 새 버전을 확인할 주소. GitHub 저장소를 만들고 OWNER/REPO를 바꿔줘.
// Releases에 update.json과 grandma-music.apk 두 파일을 올리면, 가족 설정의 "업데이트" 버튼이 항상 "가장 최신 Release"를 받아.
val updateJsonUrl = "https://github.com/bymyself-yoon/GrandmaMusicApp/releases/latest/download/update.json"

android {
    namespace = "com.suyuni.grandmamusic"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.suyuni.grandmamusic"
        // 갤럭시 A31(안드로이드 10~12)보다 한참 옛날 폰(안드로이드 8.0)까지 지원
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "UPDATE_URL", "\"$updateJsonUrl\"")
    }

    // 어느 컴퓨터에서 빌드해도 같은 키로 서명돼야 할머니 폰에 "업데이트"로 깔려.
    // 이 키 파일(signing/grandma.keystore)을 잃어버리면 업데이트가 안 되니까 꼭 백업해둬!
    signingConfigs {
        create("grandma") {
            storeFile = file("signing/grandma.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("grandma")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("grandma")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
}

// ── 배포 파일 만들기 ───────────────────────────────────────────
// Android Studio 오른쪽 Gradle 탭 → app → Tasks → other → makeUpdate 더블클릭
// (또는 터미널에서 ./gradlew :app:makeUpdate)
// → app/build/update/ 폴더에 update.json, grandma-music.apk가 생겨. 이 두 개를 GitHub Release에 올리면 끝.
tasks.register("makeUpdate") {
    group = "distribution"
    description = "업데이트용 update.json과 grandma-music.apk를 만들어"
    dependsOn("assembleRelease")
    doLast {
        val apkDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        val apk = apkDir.listFiles()?.firstOrNull { it.extension == "apk" }
            ?: throw GradleException("release APK를 못 찾았어")
        val outDir = layout.buildDirectory.dir("update").get().asFile.apply { mkdirs() }
        val outApk = File(outDir, "grandma-music.apk")
        apk.copyTo(outApk, overwrite = true)
        val sha = MessageDigest.getInstance("SHA-256").digest(outApk.readBytes())
            .joinToString("") { "%02x".format(it) }
        File(outDir, "update.json").writeText(
            """
            {
              "versionCode": $appVersionCode,
              "versionName": "$appVersionName",
              "apkUrl": "grandma-music.apk",
              "sha256": "$sha"
            }
            """.trimIndent() + "\n"
        )
        println("✅ 만들었어: ${outDir.absolutePath}")
    }
}
