# Gradle Plugin for Octane Model Generator

A Gradle Plugin providing a task to generate entity models out from ALM Octane installation

## Entity Generation

You can generate entities based on your server's metadata using the `eu.emundo.octane` plugin.
This plugin connects to your ALM Octane server using the given authentication credentials, shared space and work space
and generates strongly typed entities that can be used instead of the generic out of the box entity that comes
with the SDK1.

For more see [MicroFocus/ALMOctaneJavaRESTSDK](https://github.com/MicroFocus/ALMOctaneJavaRESTSDK)

## Getting Started

```groovy
apply plugin: "eu.emundo.octane"

octane {
    generatedSourcesDirectory = file("$buildDir/generated-sources/")
    clientId = 'Super duper clientId'
    clientSecret = 'Top secret clientSecret'
    server = 'http[s]://server[:port]'
    sharedSpace = SSID
    workSpace = WSID
}

sourceSets {
    generated{
        java.srcDir "${buildDir}/generated-sources/"
    }
}

compileGeneratedJava {
    dependsOn(generateModels)
    classpath = configurations.compile
}
compileJava{
    dependsOn(compileGeneratedJava)
    source += sourceSets.generated.java
}
```

## Versioning

We use [SemVer](http://semver.org/) for versioning. For the versions available, see the [tags on this repository](https://github.com/emundo/7z-gradle-plugin/releases).

## License

This project is licensed under the MIT License - see the [LICENSE.md](LICENSE.md) file for details
