# cappuccino

Spring native image sample

`gradle nativeCompile`

## About Gradle

### settings.gradle

Project Settings

### build.gradle

Build Scripts

## Setup Gradle for VSCode

Support workspaces without wrapper

1. Delete ./gradlew, ./gradlew.bat, ./gradle/ from the root folder.
2. Change setting: java.import.gradle.wrapper.enabled to false, java.import.gradle.version to _your-version_.

## gradle.properties

```properties
# force to use https v1.3 to resolve bad handshake
https.protocols=TLSv1.3
```

## Taskfile

CLI to combine different tasks.

## Reference

- [spring native](https://docs.spring.io/spring-native/docs/current/reference/htmlsingle)
- [GraalVM Installation](https://www.graalvm.org/22.3/docs/getting-started/macos/)
- [Gradle Installation](https://gradle.org/install/)
- [Gradle Environment Properties](https://docs.gradle.org/7.5.1/userguide/build_environment.html#sec:gradle_configuration_properties)
- [Aliyun Maven Repo](https://developer.aliyun.com/mvn/guide)
- [Taskfile Usage](https://taskfile.dev/usage/)
