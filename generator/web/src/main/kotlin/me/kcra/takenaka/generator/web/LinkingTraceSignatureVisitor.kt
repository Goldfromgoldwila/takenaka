package me.kcra.takenaka.generator.web

import me.kcra.takenaka.core.Version
import me.kcra.takenaka.generator.web.pages.fromInternalName
import net.fabricmc.mappingio.tree.MappingTree
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.Remapper
import org.objectweb.asm.signature.SignatureVisitor

/**
 * A [SignatureVisitor] that builds the Java generic type declaration corresponding to the
 * signature it visits, changing class references to HTML links.
 *
 * @author Eugene Kuleshov
 * @author Eric Bruneton
 * @author Matouš Kučera
 */
class LinkingTraceSignatureVisitor : SignatureVisitor {
    /** Whether the visited signature is a class signature of a Java interface.  */
    private val isInterface: Boolean

    /** The Java generic type declaration corresponding to the visited signature.  */
    private val declaration_: StringBuilder

    /** The Java generic method return type declaration corresponding to the visited signature.  */
    private var returnType_: StringBuilder? = null

    /** The Java generic exception types declaration corresponding to the visited signature.  */
    private var exceptions_: StringBuilder? = null

    /** Whether [.visitFormalTypeParameter] has been called.  */
    private var formalTypeParameterVisited = false

    /** Whether [.visitInterfaceBound] has been called.  */
    private var interfaceBoundVisited = false

    /** Whether [.visitParameterType] has been called.  */
    private var parameterTypeVisited = false

    /** Whether [.visitInterface] has been called.  */
    private var interfaceVisited = false

    /**
     * The stack used to keep track of class types that have arguments. Each element of this stack is
     * a boolean encoded in one bit. The top of the stack is the least significant bit. Pushing false
     * = *2, pushing true = *2+1, popping = /2.
     */
    private var argumentStack = 0

    /**
     * The stack used to keep track of array class types. Each element of this stack is a boolean
     * encoded in one bit. The top of the stack is the lowest order bit. Pushing false = *2, pushing
     * true = *2+1, popping = /2.
     */
    private var arrayStack = 0

    /** The separator to append before the next visited class or inner class type.  */
    private var separator = ""

    /**
     * The mapping tree used for remapping.
     */
    private val tree: MappingTree

    /**
     * The class name remapper.
     */
    private val remapper: Remapper

    /**
     * The version of the mappings.
     */
    private val version: Version

    private val classNames: MutableList<String> = mutableListOf()

    val declaration: String get() = declaration_.toString()
    val returnType: String? get() = returnType_?.toString()
    val exceptions: String? get() = exceptions_?.toString()

    /**
     * Constructs a new [LinkingTraceSignatureVisitor].
     *
     * @param accessFlags for class type signatures, the access flags of the class.
     */
    constructor(tree: MappingTree, remapper: Remapper, version: Version, accessFlags: Int) : super(Opcodes.ASM9) {
        isInterface = accessFlags and Opcodes.ACC_INTERFACE != 0
        declaration_ = StringBuilder()
        this.remapper = remapper
        this.tree = tree
        this.version = version
    }

    private constructor(tree: MappingTree, remapper: Remapper, version: Version, stringBuilder: StringBuilder) : super(Opcodes.ASM9) {
        isInterface = false
        declaration_ = stringBuilder
        this.remapper = remapper
        this.tree = tree
        this.version = version
    }

    override fun visitFormalTypeParameter(name: String) {
        declaration_.append(if (formalTypeParameterVisited) COMMA_SEPARATOR else "&lt;").append(name)
        formalTypeParameterVisited = true
        interfaceBoundVisited = false
    }

    override fun visitClassBound(): SignatureVisitor {
        separator = EXTENDS_SEPARATOR
        startType()
        return this
    }

    override fun visitInterfaceBound(): SignatureVisitor {
        separator = if (interfaceBoundVisited) COMMA_SEPARATOR else EXTENDS_SEPARATOR
        interfaceBoundVisited = true
        startType()
        return this
    }

    override fun visitSuperclass(): SignatureVisitor {
        endFormals()
        separator = EXTENDS_SEPARATOR
        startType()
        return this
    }

    override fun visitInterface(): SignatureVisitor {
        if (interfaceVisited) {
            separator = COMMA_SEPARATOR
        } else {
            separator = if (isInterface) EXTENDS_SEPARATOR else IMPLEMENTS_SEPARATOR
            interfaceVisited = true
        }
        startType()
        return this
    }

    override fun visitParameterType(): SignatureVisitor {
        endFormals()
        if (parameterTypeVisited) {
            declaration_.append(COMMA_SEPARATOR)
        } else {
            declaration_.append('(')
            parameterTypeVisited = true
        }
        startType()
        return this
    }

    override fun visitReturnType(): SignatureVisitor {
        endFormals()
        if (parameterTypeVisited) {
            parameterTypeVisited = false
        } else {
            declaration_.append('(')
        }
        declaration_.append(')')
        StringBuilder().also { builder ->
            returnType_ = builder
            return LinkingTraceSignatureVisitor(tree, remapper, version, builder)
        }
    }

    override fun visitExceptionType(): SignatureVisitor =
        LinkingTraceSignatureVisitor(tree, remapper, version, exceptions_?.append(COMMA_SEPARATOR) ?: StringBuilder().also { exceptions_ = it })

    override fun visitBaseType(descriptor: Char) {
        val baseType = BASE_TYPES[descriptor] ?: throw IllegalArgumentException()
        declaration_.append(baseType)
        endType()
    }

    override fun visitTypeVariable(name: String) {
        declaration_.append(separator).append(name)
        separator = ""
        endType()
    }

    override fun visitArrayType(): SignatureVisitor {
        startType()
        arrayStack = arrayStack or 1
        return this
    }

    override fun visitClassType(name: String) {
        classNames.add(name)

        if ("java/lang/Object" == name) {
            // 'Map<java.lang.Object,java.util.List>' or 'abstract public V get(Object key);' should have
            // Object 'but java.lang.String extends java.lang.Object' is unnecessary.
            val needObjectClass = argumentStack % 2 != 0 || parameterTypeVisited
            if (needObjectClass) {
                declaration_.append(separator).append(remapper.mapTypeAndLink(version, name))
            }
        } else {
            declaration_.append(separator).append(remapper.mapTypeAndLink(version, name))
        }
        separator = ""
        argumentStack *= 2
    }

    override fun visitInnerClassType(name: String) {
        val outerClassName = classNames.removeAt(classNames.size - 1)
        val className = "$outerClassName$$name"
        classNames += className
        val remappedOuter = remapper.mapType(outerClassName) + '$'
        val remappedName = remapper.mapType(className)
        val index =
            if (remappedName.startsWith(remappedOuter)) remappedOuter.length else remappedName.lastIndexOf('$') + 1

        if (argumentStack % 2 != 0) {
            declaration_.append("&gt;")
        }
        argumentStack /= 2
        declaration_.append('.')
        declaration_.append(separator).append(
            if (remappedName != className) {
                """<a href="/${version.id}/$remappedName.html">${remappedName.substring(index)}</a>"""
            } else {
                remappedName.substring(index)
            }
        )
        separator = ""
        argumentStack *= 2
    }

    override fun visitTypeArgument() {
        if (argumentStack % 2 == 0) {
            ++argumentStack
            declaration_.append("&lt;")
        } else {
            declaration_.append(COMMA_SEPARATOR)
        }
        declaration_.append('?')
    }

    override fun visitTypeArgument(tag: Char): SignatureVisitor {
        if (argumentStack % 2 == 0) {
            ++argumentStack
            declaration_.append("&lt;")
        } else {
            declaration_.append(COMMA_SEPARATOR)
        }
        if (tag == EXTENDS) {
            declaration_.append("? extends ")
        } else if (tag == SUPER) {
            declaration_.append("? super ")
        }
        startType()
        return this
    }

    override fun visitEnd() {
        if (argumentStack % 2 != 0) {
            declaration_.append("&gt;")
        }
        argumentStack /= 2
        endType()
        classNames.removeAt(classNames.size - 1)
    }

    private fun endFormals() {
        if (formalTypeParameterVisited) {
            declaration_.append("&gt;")
            formalTypeParameterVisited = false
        }
    }

    private fun startType() {
        arrayStack *= 2
    }

    private fun endType() {
        if (arrayStack % 2 == 0) {
            arrayStack /= 2
        } else {
            while (arrayStack % 2 != 0) {
                arrayStack /= 2
                declaration_.append("[]")
            }
        }
    }

    companion object {
        private const val COMMA_SEPARATOR = ", "
        private const val EXTENDS_SEPARATOR = " extends "
        private const val IMPLEMENTS_SEPARATOR = " implements "
        private val BASE_TYPES: Map<Char, String> = mapOf(
            'Z' to "boolean",
            'B' to "byte",
            'C' to "char",
            'S' to "short",
            'I' to "int",
            'J' to "long",
            'F' to "float",
            'D' to "double",
            'V' to "void",
        )
    }
}

/**
 * Remaps a type and creates a link if a mapping has been found.
 *
 * @param version the mapping version
 * @param internalName the internal name of the class to be remapped
 * @return the remapped type, a link if it was found
 */
fun Remapper.mapTypeAndLink(version: Version, internalName: String): String {
    val remappedName = mapType(internalName)

    return if (remappedName != internalName) {
        """<a href="/${version.id}/$remappedName.html">${remappedName.substringAfterLast('/')}</a>"""
    } else {
        remappedName.fromInternalName()
    }
}
