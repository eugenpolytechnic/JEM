package org.jetbrains.research.jem.plugin

import com.intellij.openapi.project.Project
import com.intellij.psi.*
import javassist.bytecode.Descriptor
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCall
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.source.getPsi
import org.jetbrains.kotlinx.serialization.compiler.resolve.toClassDescriptor
import org.jetbrains.research.jem.analysis.MethodAnalyzer
import org.jetbrains.research.jem.interaction.*
import org.jetbrains.research.jem.plugin.JavaCaretAnalyzer.getJarPath
import org.jetbrains.research.jem.plugin.KotlinCaretAnalyzer.getJarPath
import java.io.File

object JavaCaretAnalyzer {

    fun analyze(psiFile: PsiFile, project: Project, startOffset: Int, endOffset: Int)
            : Map<String, Set<Discovery>> =
        getDiscoveredExceptionsMap(
            JCallExtractor(
                psiFile,
                startOffset,
                endOffset
            ).extract(),
            project
        )

    fun descriptorFor(method: PsiMethod): String =
            buildString {
                append("(")
                method.parameterList.parameters.forEach {
                    append(Descriptor.of(it.type.canonicalText))
                }
                append(")")
                append(Descriptor.of(method.returnType?.canonicalText ?: "void"))
            }

    private fun getDiscoveredExceptionsMap(psiMethodCalls: Set<PsiCall>, project: Project?)
            : Map<String, Set<Discovery>> {
        val result = mutableMapOf<String, MutableSet<Discovery>>()
        if (project == null)
            return emptyMap()
        if (!LazyPolymorphAnalyzer.isInit()) {
            LazyPolymorphAnalyzer.init(project)
        }
        for (call in psiMethodCalls) {
            val method = call.resolveMethod() ?: continue
            if (method.notInJar()) {
                continue
            }
            val exceptions = getExceptionsFor(method, false)
            exceptions.forEach {
                val discovery = Discovery(it, call, method)
                result.getOrPut(it) { mutableSetOf() }.add(discovery)
            }
        }
        return result
    }

    fun PsiMethod.getJarPath() =
            this.containingFile.virtualFile.toString()
            .replaceAfterLast(".jar", "")
            .replaceBefore("://", "")
            .removePrefix("://")
}

object KotlinCaretAnalyzer {

    fun analyze(psiFile: PsiFile, project: Project, startOffset: Int, endOffset: Int)
            : Map<String, Set<Discovery>> =
        getDiscoveredExceptionsMap(
            KCallExtractor(
                psiFile as KtFile,
                startOffset,
                endOffset).extract(),
            project
        )

    fun CallableDescriptor.getJarPath(): String =
            this.findPsi()!!.containingFile.virtualFile.toString()
                .replaceAfterLast(".jar", "")
                .replaceBefore("://", "")
                .removePrefix("://")

    private fun getDiscoveredExceptionsMap(psiMethodCalls: Set<KtCallElement>, project: Project?)
            : Map<String, Set<Discovery>> {
        val result = mutableMapOf<String, MutableSet<Discovery>>()
        if (project == null)
            return emptyMap()
        if (!LazyPolymorphAnalyzer.isInit()) {
            LazyPolymorphAnalyzer.init(project)
        }
        for (call in psiMethodCalls) {
            val method = call.getResolvedCall(call.analyze())?.resultingDescriptor ?: continue
            if (method.findPsi()?.notInJar() != false) {
                continue
            }
            val exceptions = getExceptionsFor(method, true)
            exceptions.forEach {
                val discovery = Discovery(it, call, method.findPsi() as PsiMethod)
                result.getOrPut(it) { mutableSetOf() }.add(discovery)
            }
        }
        return result
    }

    fun descriptorFor(method: CallableDescriptor): String =
            buildString {
                append("(")
                method.valueParameters.forEach {
                    append(Descriptor.of(it.source.getPsi()?.text?.replace(" classname", "")))
                }
                append(")")
                append(Descriptor.of(method.returnType.toClassDescriptor?.fqNameSafe.toString()))
            }
}

private fun PsiElement.notInJar(): Boolean =
        !(this.containingFile?.virtualFile.toString().startsWith("jar"))

private fun <T> getExceptionsFor(method: T, isKotlin: Boolean): Set<String> {
    val jarPath: String
    val name: String
    val clazz: String
    val descriptor: String
    if (isKotlin) {
        val m = method as CallableDescriptor
        jarPath = m.getJarPath()
        name = m.name.toString()
        clazz = m.containingDeclaration.fqNameSafe.toString()
        descriptor = KotlinCaretAnalyzer.descriptorFor(m)
    } else {
        val m = method as PsiMethod
        jarPath = m.getJarPath()
        name = m.name
        clazz = m.containingClass?.qualifiedName.toString()
        descriptor = JavaCaretAnalyzer.descriptorFor(m)
    }
    val jsonPath = System.getProperty("user.home") +
            "/.JEMPluginCache/" +
            clazz
                .replace("(\\.[A-Z].*)".toRegex(), "")
                .replace(".", "/") +
            "/${clazz.replace("(\\.[A-Z].*)".toRegex(), "")}.json"
    if (!File(jsonPath).exists()) {
        MethodAnalyzer.polyMethodsExceptions =
            emptyMap<MethodInformation, Set<String>>().toMutableMap()
        val methodInfo = MethodInformation(clazz, name, descriptor)
        val exceptions = LazyPolymorphAnalyzer.analyze(methodInfo)
        if (exceptions != null)
            return exceptions
        JarAnalyzer.analyze(jarPath, false)
    }
    return InfoReader.getAllExceptionsFor(MethodInformation(clazz, name, descriptor))
}