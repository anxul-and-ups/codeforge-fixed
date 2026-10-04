package com.example.data.github

/** GitHub Actions workflow that builds the debug APK on every push (any branch). */
object BuildWorkflow {
    const val PATH = ".github/workflows/build.yml"

    val YAML = """
name: Build Debug APK

on:
  push:
  workflow_dispatch:

jobs:
  build:
    name: Build Debug APK
    runs-on: ubuntu-latest

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: 'gradle'

      - name: Make gradlew executable
        run: chmod +x gradlew || true

      - name: Create local.properties
        run: echo "sdk.dir=${'$'}ANDROID_HOME" > local.properties

      - name: Build with Gradle
        run: |
          if [ -f "./gradlew" ]; then
            ./gradlew assembleDebug --stacktrace
          else
            gradle assembleDebug --stacktrace
          fi

      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: debug-apk
          path: '**/build/outputs/apk/debug/*.apk'
          retention-days: 14
""".trimStart()
}
