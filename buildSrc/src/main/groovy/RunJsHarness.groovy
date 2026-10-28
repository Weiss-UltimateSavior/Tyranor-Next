import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

/** Run a Node harness with the same actionable diagnostics for launch and test failures. */
abstract class RunJsHarness extends DefaultTask {
    @Input abstract Property<String> getNodeExecutable()
    @InputFile @PathSensitive(PathSensitivity.RELATIVE)
    abstract RegularFileProperty getScriptFile()
    @Inject abstract ExecOperations getExecOperations()

    RunJsHarness() { nodeExecutable.convention('node') }

    @TaskAction void runHarness() {
        File script = scriptFile.get().asFile
        try {
            execOperations.exec {
                workingDir script.parentFile
                commandLine nodeExecutable.get(), script.absolutePath
                ignoreExitValue = true
            }.assertNormalExitValue()
        } catch (Exception error) {
            throw new GradleException(
                name + ' 执行失败（需要 Node.js）。若缺少 node：安装后重试，' +
                '或用 ./gradlew check -PskipJsHarness 跳过；若为用例失败请查看上方输出。' +
                ' 原始错误：' + error.message, error)
        }
    }
}
