#!/usr/bin/env fish
# Bootstrap dhsn-stundenplan-widget (Kotlin + Glance + Material You)
# Run: fish bootstrap.fish

set -l root (pwd)

# --- Verzeichnisse ----------------------------------------------------------
mkdir -p $root/app/src/main/kotlin/de/dhsn/stundenplan/{ui,data,work}
mkdir -p $root/app/src/main/res/{drawable,values,xml}

# --- Gradle (root) ----------------------------------------------------------
cat > $root/settings.gradle.kts << 'GRADLE_EOF'
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "dhsn-stundenplan-widget"
include(":app")
GRADLE_EOF

cat > $root/build.gradle.kts << 'GRADLE_EOF'
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
GRADLE_EOF

cat > $root/gradle.properties << 'PROPS_EOF'
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
PROPS_EOF

# --- App-Modul --------------------------------------------------------------
cat > $root/app/build.gradle.kts << 'APP_EOF'
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "de.dhsn.stundenplan"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.dhsn.stundenplan"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.glance:glance:1.1.0")
    implementation("androidx.glance:glance-material3:1.1.0")
    implementation("androidx.glance:glance-appwidget:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
}
APP_EOF

# --- AndroidManifest --------------------------------------------------------
cat > $root/app/src/main/AndroidManifest.xml << 'MANIFEST_EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:name=".StundenplanApp"
        android:label="@string/app_name"
        android:allowBackup="true">
        <receiver
            android:name=".StundenplanWidgetReceiver"
            android:exported="false">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data
                android:name="android.appwidget.provider"
                android:resource="@xml/stundenplan_widget_info" />
        </receiver>
    </application>
</manifest>
MANIFEST_EOF

# --- res/xml ---------------------------------------------------------------
cat > $root/app/src/main/res/xml/stundenplan_widget_info.xml << 'XML_EOF'
<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:initialLayout="@layout/glance_default_loading_layout"
    android:minWidth="180dp"
    android:minHeight="110dp"
    android:targetCellWidth="3"
    android:targetCellHeight="2"
    android:resizeMode="horizontal|vertical"
    android:updatePeriodMillis="1800000"
    android:widgetCategory="home_screen"
    android:previewLayout="@layout/glance_default_loading_layout"
    android:description="@string/widget_description" />
XML_EOF

# --- res/values ------------------------------------------------------------
cat > $root/app/src/main/res/values/strings.xml << 'STRINGS_EOF'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">DHSN Stundenplan</string>
    <string name="widget_description">Stundenplan als Widget mit Material You</string>
    <string name="widget_label">Stundenplan</string>
</resources>
STRINGS_EOF

# --- Kotlin-Quellen ---------------------------------------------------------
cat > $root/app/src/main/kotlin/de/dhsn/stundenplan/StundenplanApp.kt << 'KT_EOF'
package de.dhsn.stundenplan

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import de.dhsn.stundenplan.work.WidgetRefreshWorker
import java.util.concurrent.TimeUnit

class StundenplanApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val req = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(6, TimeUnit.HOURS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "widget-refresh",
            ExistingPeriodicWorkPolicy.UPDATE,
            req
        )
    }
}
KT_EOF

cat > $root/app/src/main/kotlin/de/dhsn/stundenplan/StundenplanWidget.kt << 'KT_EOF'
package de.dhsn.stundenplan

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import de.dhsn.stundenplan.data.TimetableRepository
import de.dhsn.stundenplan.ui.WidgetContent

class StundenplanWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val lessons = TimetableRepository.today(context)
        provideContent {
            GlanceTheme {
                WidgetContent(lessons = lessons)
            }
        }
    }
}
KT_EOF

cat > $root/app/src/main/kotlin/de/dhsn/stundenplan/StundenplanWidgetReceiver.kt << 'KT_EOF'
package de.dhsn.stundenplan

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

class StundenplanWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StundenplanWidget()
}
KT_EOF

cat > $root/app/src/main/kotlin/de/dhsn/stundenplan/data/TimetableRepository.kt << 'KT_EOF'
package de.dhsn.stundenplan.data

import android.content.Context
import java.time.LocalDate

data class Lesson(val time: String, val subject: String, val room: String)

object TimetableRepository {
    fun today(context: Context): List<Lesson> {
        val day = LocalDate.now().dayOfWeek.value
        return when (day) {
            1 -> listOf(
                Lesson("08:00", "Mathe", "A101"),
                Lesson("09:45", "Deutsch", "B202"),
                Lesson("11:30", "Englisch", "C303")
            )
            2 -> listOf(
                Lesson("08:00", "Physik", "A201"),
                Lesson("09:45", "Mathe", "A101"),
                Lesson("11:30", "Sport", "Halle")
            )
            else -> emptyList()
        }
    }
}
KT_EOF

cat > $root/app/src/main/kotlin/de/dhsn/stundenplan/ui/WidgetContent.kt << 'KT_EOF'
package de.dhsn.stundenplan.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import de.dhsn.stundenplan.data.Lesson

@Composable
fun WidgetContent(lessons: List<Lesson>) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(16.dp)
            .background(GlanceTheme.colors.primaryContainer)
    ) {
        Text(
            text = "Heute",
            style = TextStyle(
                color = GlanceTheme.colors.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        )
        if (lessons.isEmpty()) {
            Text(
                text = "Keine Stunden",
                style = TextStyle(
                    color = GlanceTheme.colors.onPrimaryContainer,
                    fontSize = 13.sp
                )
            )
        } else {
            lessons.take(4).forEach { lesson ->
                Text(
                    text = "${lesson.time}  ${lesson.subject}  ${lesson.room}",
                    style = TextStyle(
                        color = GlanceTheme.colors.onPrimaryContainer,
                        fontSize = 13.sp
                    )
                )
            }
        }
    }
}
KT_EOF

cat > $root/app/src/main/kotlin/de/dhsn/stundenplan/work/WidgetRefreshWorker.kt << 'KT_EOF'
package de.dhsn.stundenplan.work

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.dhsn.stundenplan.StundenplanWidget

class WidgetRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        StundenplanWidget().updateAll(appContext)
        return Result.success()
    }
}
KT_EOF

echo "Done."
find $root -type f -not -path '*/.git/*' | sort