package org.jetbrains.kotlin.backend.konan.ir.interop

import org.jetbrains.kotlin.backend.konan.InteropBuiltIns
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.util.SymbolTable
import org.jetbrains.kotlin.psi2ir.generators.GeneratorContext
import org.jetbrains.kotlin.resolve.descriptorUtil.getAllSuperClassifiers
import org.jetbrains.kotlin.resolve.descriptorUtil.parentsWithSelf
import org.jetbrains.kotlin.types.KotlinType

internal inline fun <reified T: DeclarationDescriptor> ClassDescriptor.findDeclarationByName(name: String): T? =
        defaultType.memberScope
                .getContributedDescriptors()
                .filterIsInstance<T>()
                .firstOrNull { it.name.identifier == name }

internal interface GeneratorContextAware {
    val context: GeneratorContext

    val symbolTable: SymbolTable
            get() = context.symbolTable

    fun KotlinType.toIrType() = context.typeTranslator.translateType(this)
}


internal fun ClassDescriptor.implementsCEnum(interopBuiltIns: InteropBuiltIns): Boolean =
        interopBuiltIns.cEnum in this.getAllSuperClassifiers()

internal fun IrSymbol.findCEnumDescriptor(interopBuiltIns: InteropBuiltIns): ClassDescriptor? =
        descriptor.parentsWithSelf
                .filterIsInstance<ClassDescriptor>()
                .firstOrNull { it.implementsCEnum(interopBuiltIns) }