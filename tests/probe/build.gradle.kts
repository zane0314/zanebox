plugins { id("com.android.application") version "8.7.3" }
android {
 namespace="com.zane.probe"; compileSdk=35
 defaultConfig { applicationId="com.zane.probe"; minSdk=23; targetSdk=35; versionCode=1; versionName="1" }
 compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
}
