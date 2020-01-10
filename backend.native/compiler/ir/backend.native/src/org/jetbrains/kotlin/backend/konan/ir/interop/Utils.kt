package org.jetbrains.kotlin.backend.konan.ir.interop

import org.jetbrains.kotlin.backend.common.ir.allParameters
import org.jetbrains.kotlin.backend.common.ir.copyParameterDeclarationsFrom
import org.jetbrains.kotlin.backend.konan.InteropBuiltIns
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrFunctionImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrPropertyImpl
import org.jetbrains.kotlin.ir.descriptors.WrappedPropertyDescriptor
import org.jetbrains.kotlin.ir.descriptors.WrappedSimpleFunctionDescriptor
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrPropertySymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.util.SymbolTable
import org.jetbrains.kotlin.ir.util.isGetter
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

fun IrClass.addFakeOverridesWithProperties() {
    fun IrDeclaration.toList() = when (this) {
        is IrSimpleFunction -> listOf(this)
        is IrProperty -> listOfNotNull(getter, setter)
        else -> emptyList()
    }

    val overriddenDeclarations = declarations
            .flatMap { it.toList() }
            .flatMap { it.overriddenSymbols.map { it.owner } }
            .toSet()

    val unoverriddenSuperFunctions = superTypes
            .map { it.getClass()!! }
            .flatMap { irClass ->
                irClass.declarations
                        .flatMap { it.toList() }
                        .filter { it !in overriddenDeclarations }
                        .filter { it.visibility != Visibilities.PRIVATE }
            }
            .toSet()

    // TODO: A dirty hack.
    val groupedUnoverriddenSuperFunctions = unoverriddenSuperFunctions
            .groupBy { it.name.asString() + it.allParameters.size }.values

    val properties = mutableMapOf<IrProperty, IrProperty>()

    fun createFakeOverride(overriddenFunctions: List<IrSimpleFunction>): IrSimpleFunction? =
            overriddenFunctions.first().let { irFunction ->
                val descriptor = WrappedSimpleFunctionDescriptor()
                IrFunctionImpl(
                        UNDEFINED_OFFSET,
                        UNDEFINED_OFFSET,
                        IrDeclarationOrigin.FAKE_OVERRIDE,
                        IrSimpleFunctionSymbolImpl(descriptor),
                        irFunction.name,
                        Visibilities.INHERITED,
                        irFunction.modality,
                        irFunction.returnType,
                        isInline = irFunction.isInline,
                        isExternal = irFunction.isExternal,
                        isTailrec = irFunction.isTailrec,
                        isSuspend = irFunction.isSuspend,
                        isExpect = irFunction.isExpect,
                        isFakeOverride = true,
                        isOperator = irFunction.isOperator
                ).run {
                    descriptor.bind(this)
                    parent = this@addFakeOverridesWithProperties
                    overriddenSymbols += overriddenFunctions.map { it.symbol }
                    copyParameterDeclarationsFrom(irFunction)
                    if (irFunction.isPropertyAccessor) {
                        val originalProperty = irFunction.correspondingPropertySymbol!!.owner
                        val property = properties.getOrPut(originalProperty) {
                            val propertyDescriptor = WrappedPropertyDescriptor()
                            IrPropertyImpl(
                                    UNDEFINED_OFFSET,
                                    UNDEFINED_OFFSET,
                                    IrDeclarationOrigin.FAKE_OVERRIDE,
                                    IrPropertySymbolImpl(propertyDescriptor),
                                    originalProperty.name,
                                    originalProperty.visibility,
                                    originalProperty.modality,
                                    originalProperty.isVar,
                                    originalProperty.isConst,
                                    originalProperty.isLateinit,
                                    originalProperty.isDelegated,
                                    originalProperty.isExternal,
                                    originalProperty.isExpect
                            ).also {
                                propertyDescriptor.bind(it)
                                it.parent = this@addFakeOverridesWithProperties
                            }
                        }
                        if (this.isGetter) {
                            property.getter = this
                        } else {
                            property.setter = this
                        }
                        return null
                    } else {
                        return this
                    }
                }
            }


    val fakeOverriddenFunctions = groupedUnoverriddenSuperFunctions
            .asSequence().associateWith { createFakeOverride(it) }
            .toMutableMap()

    declarations += fakeOverriddenFunctions.values.filterNotNull()
    declarations += properties.values
}