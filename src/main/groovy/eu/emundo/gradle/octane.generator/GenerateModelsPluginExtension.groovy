package eu.emundo.gradle.octane.generator

import org.gradle.api.tasks.OutputDirectory

class GenerateModelsPluginExtension {
    @OutputDirectory
    File generatedSourcesDirectory
    String clientId
    String clientSecret
    String server
    long sharedSpace
    long workSpace
}
