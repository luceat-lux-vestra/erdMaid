package com.algorist.erdmaid.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class ErdMaidExportActionTest {
    private val action = ErdMaidExportAction()

    @Test
    fun `action declares bounded EDT update policy`() {
        assertEquals(ActionUpdateThread.EDT, action.actionUpdateThread)

        val project = project(disposed = false)
        val event = createEvent(project)
        action.update(event)
        assertTrue(event.presentation.isEnabledAndVisible)
    }

    @Test
    fun `update hides action without a live project`() {
        val noProject = createEvent(null)
        action.update(noProject)
        assertFalse(noProject.presentation.isEnabledAndVisible)

        val disposed = createEvent(project(disposed = true))
        action.update(disposed)
        assertFalse(disposed.presentation.isEnabledAndVisible)
    }

    @Test
    fun `action has no invocation state reflection legacy generator or fatal-error path`() {
        val instanceFields = ErdMaidExportAction::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
        assertTrue(instanceFields.isEmpty())

        val source = Files.readString(
            Path.of("src/main/kotlin/com/algorist/erdmaid/actions/ErdMaidExportActions.kt")
        )
        assertTrue(source.contains("e.coroutineScope"))
        assertTrue(source.contains("withBackgroundProgress"))
        assertTrue(source.contains("cancellable = true"))
        assertTrue(source.contains("Dispatchers.EDT"))
        assertFalse(source.contains("Class.forName"))
        assertFalse(source.contains("getSelectedDbElementsExpandingGroups"))
        assertFalse(source.contains("MermaidGenerator"))
        assertFalse(source.contains("LOG.error"))
    }

    @Test
    fun `metadata adapter owns cancellable read actions instead of action`() {
        val actionSource = Files.readString(
            Path.of("src/main/kotlin/com/algorist/erdmaid/actions/ErdMaidExportActions.kt")
        )
        val hostSource = Files.readString(
            Path.of("src/main/kotlin/com/algorist/erdmaid/host/JetBrainsDatabaseHost.kt")
        )

        assertFalse(actionSource.contains("readAction"))
        assertTrue(hostSource.contains("readAction"))
    }

    @Test
    fun `plugin xml registers only one export action`() {
        val pluginXml = javaClass.classLoader
            .getResourceAsStream("META-INF/plugin.xml")
            ?.readBytes()
            ?.toString(StandardCharsets.UTF_8)
            ?: error("plugin.xml resource not found")

        assertEquals(
            1,
            Regex("""<action id="com\.algorist\.erdmaid\.actions\.ErdMaidExportAction""")
                .findAll(pluginXml)
                .count(),
        )
    }

    private fun createEvent(project: Project?): AnActionEvent {
        val dataContext = com.intellij.openapi.actionSystem.DataContext { dataId ->
            when (dataId) {
                CommonDataKeys.PROJECT.name -> project
                else -> null
            }
        }
        return AnActionEvent.createFromAnAction(action, null, "erdMaid-test", dataContext)
    }

    private fun project(disposed: Boolean): Project =
        proxy(Project::class.java) { method, _ ->
            when (method.name) {
                "isDisposed" -> disposed
                "toString" -> "Project(test)"
                else -> defaultValue(method.returnType)
            }
        }

    private fun <T> proxy(
        type: Class<T>,
        handler: (Method, Array<out Any?>?) -> Any?,
    ): T {
        val invocationHandler = InvocationHandler { _, method, args -> handler(method, args) }
        return type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type), invocationHandler))!!
    }

    private fun defaultValue(returnType: Class<*>): Any? = when (returnType) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }
}
