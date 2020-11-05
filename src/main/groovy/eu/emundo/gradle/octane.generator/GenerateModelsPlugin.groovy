package eu.emundo.gradle.octane.generator

import com.hpe.adm.nga.sdk.exception.OctaneException
import eu.emundo.generator.generate.GenerateModels
import org.gradle.api.GradleScriptException
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Plugin
import org.gradle.api.Project


class GenerateModelsPlugin implements Plugin<Project> {

    @Override
    void apply(final Project project) {
        project.task("generateModels") {
            group = 'octane'
            def extension = project.extensions.create("octane", GenerateModelsPluginExtension)
            doLast {
                try {
                    println("Starting to generate entities")
                    new GenerateModels(extension.generatedSourcesDirectory)
                            .generate(extension.clientId, extension.clientSecret, extension.server, extension.sharedSpace, extension.workSpace,
                                    extension.doNotValidateCertificate, extension.techPreview)
                } catch (IOException e) {
                    throw new InvalidUserDataException("Problem generating entities", e)
                } catch (OctaneException e) {
                    throw new GradleScriptException("Problem getting Octane data", e)
                }
                println("Finished generating entities")
            }
        }
    }
}
