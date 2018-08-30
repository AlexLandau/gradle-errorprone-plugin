package net.ltgt.gradle.errorprone.javacplugin

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.util.GradleVersion
import org.gradle.util.TextUtil
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

abstract class AbstractPluginIntegrationTest {

    companion object {
        internal const val FAILURE_SOURCE_COMPILATION_ERROR = "Failure.java:6: error: [ArrayEquals]"
    }

    @JvmField
    @Rule
    val testProjectDir = TemporaryFolder()

    lateinit var settingsFile: File
    lateinit var buildFile: File

    private val testJavaHome = System.getProperty("test.java-home")
    private val testGradleVersion = System.getProperty("test.gradle-version", GradleVersion.current().version)
    private val pluginVersion = System.getProperty("plugin.version")!!

    protected val errorproneVersion = System.getProperty("errorprone.version")!!
    private val errorproneJavacVersion = System.getProperty("errorprone-javac.version")!!

    protected val supportsLazyTasks = ErrorProneJavacPluginPlugin.supportsLazyTasks(GradleVersion.version(testGradleVersion))
    protected val configureEachIfSupported = ".configureEach".takeIf { supportsLazyTasks }.orEmpty()

    protected open val additionalPluginManagementRepositories: String = ""

    protected open val additionalPluginManagementResolutionStrategyEachPlugin: String = ""

    @Before
    fun setupProject() {
        // See https://github.com/gradle/kotlin-dsl/issues/492
        val testRepository = TextUtil.normaliseFileSeparators(File("build/repository").absolutePath)
        settingsFile = testProjectDir.newFile("settings.gradle.kts").apply {
            writeText("""
                pluginManagement {
                    repositories {
                        maven { url = uri("$testRepository") }
                        $additionalPluginManagementRepositories
                    }
                    resolutionStrategy {
                        eachPlugin {
                            if (requested.id.id == "${ErrorProneJavacPluginPlugin.PLUGIN_ID}") {
                                useVersion("$pluginVersion")
                            }
                            $additionalPluginManagementResolutionStrategyEachPlugin
                        }
                    }
                }

            """.trimIndent())
        }
        buildFile = testProjectDir.newFile("build.gradle.kts").apply {
            writeText("""
                import net.ltgt.gradle.errorprone.javacplugin.*

            """.trimIndent())
        }
    }

    protected fun writeSuccessSource() {
        File(testProjectDir.newFolder("src", "main", "java", "test"), "Success.java").apply {
            createNewFile()
            writeText("""
                package test;

                public class Success {
                    // See http://errorprone.info/bugpattern/ArrayEquals
                    @SuppressWarnings("ArrayEquals")
                    public boolean arrayEquals(int[] a, int[] b) {
                        return a.equals(b);
                    }
                }
            """.trimIndent())
        }
    }

    protected fun writeFailureSource() {
        File(testProjectDir.newFolder("src", "main", "java", "test"), "Failure.java").apply {
            createNewFile()
            writeText("""
                package test;

                public class Failure {
                    // See http://errorprone.info/bugpattern/ArrayEquals
                    public boolean arrayEquals(int[] a, int[] b) {
                        return a.equals(b);
                    }
                }
            """.trimIndent())
        }
    }

    protected fun buildWithArgs(vararg tasks: String): BuildResult {
        return prepareBuild(*tasks)
            .build()
    }

    protected fun buildWithArgsAndFail(vararg tasks: String): BuildResult {
        return prepareBuild(*tasks)
            .buildAndFail()
    }

    private fun prepareBuild(vararg tasks: String): GradleRunner {
        testJavaHome?.also {
            buildFile.appendText("""

                tasks.withType<JavaCompile>()$configureEachIfSupported {
                    options.isFork = true
                    options.forkOptions.javaHome = File(""${'"'}${it.replace("\$", "\${'\$'}")}${'"'}"")
                }
            """.trimIndent())
        }
        buildFile.appendText("""

            // XXX: cannot use JavaVersion#isJava8 in Gradle 4.6
            if (JavaVersion.current() == JavaVersion.VERSION_1_8
                // This needs to be idempotent, in case the method is called multiple times
                && "errorproneJavac" !in configurations.names) {
                val errorproneJavac by configurations.creating
                dependencies {
                    errorproneJavac("com.google.errorprone:javac:$errorproneJavacVersion")
                }
                tasks.withType<JavaCompile>()$configureEachIfSupported {
                    if (options.forkOptions.javaHome == null) {
                        inputs.files(errorproneJavac)
                        options.isFork = true
                        doFirst {
                            options.forkOptions.jvmArgs!!.add("-Xbootclasspath/p:${'$'}{errorproneJavac.asPath}")
                        }
                    }
                }
            }
        """.trimIndent())

        return GradleRunner.create()
            .withGradleVersion(testGradleVersion)
            .withProjectDir(testProjectDir.root)
            .withArguments(*tasks)
    }
}
