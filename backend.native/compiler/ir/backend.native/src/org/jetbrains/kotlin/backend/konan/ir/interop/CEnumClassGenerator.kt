package org.jetbrains.kotlin.backend.konan.ir.interop

import org.jetbrains.kotlin.backend.common.ir.createDispatchReceiverParameter
import org.jetbrains.kotlin.backend.common.ir.createParameterDeclarations
import org.jetbrains.kotlin.backend.konan.descriptors.enumEntries
import org.jetbrains.kotlin.backend.konan.ir.KonanSymbols
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi2ir.generators.DeclarationGenerator
import org.jetbrains.kotlin.psi2ir.generators.EnumClassMembersGenerator
import org.jetbrains.kotlin.psi2ir.generators.GeneratorContext
import org.jetbrains.kotlin.utils.addToStdlib.firstNotNullResult

internal class CEnumClassGenerator(override val context: GeneratorContext) : GeneratorContextAware {

    private val enumClassMembersGenerator = EnumClassMembersGenerator(DeclarationGenerator(context))

    private val cEnumClassCompanionGenerator = CEnumClassCompanionGenerator(context)

    private val cEnumVarClassGenerator = CEnumVarClassGenerator(context)

    fun findAndGenerateCEnum(classDescriptor: ClassDescriptor, parent: IrDeclarationContainer): IrClass {
        val irClassSymbol = symbolTable.referenceClass(classDescriptor)
        return if (!irClassSymbol.isBound) {
            provideIrClassForCEnum(classDescriptor).also {
                it.patchDeclarationParents(parent)
                parent.declarations += it
            }
        } else {
            irClassSymbol.owner
        }
    }

    private fun createPropertyWithBackingField(irClass: IrClass, name: String): IrProperty {
        val propertyDescriptor = irClass.descriptor
                .findDeclarationByName<PropertyDescriptor>(name)
                ?: error("No `$name` property")
        val irProperty = symbolTable.declareProperty(
                SYNTHETIC_OFFSET, SYNTHETIC_OFFSET,
                IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB, propertyDescriptor
        )
        symbolTable.withScope(propertyDescriptor) {
            irProperty.parent = irClass
            irProperty.backingField = symbolTable.declareField(
                    SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, IrDeclarationOrigin.PROPERTY_BACKING_FIELD,
                    propertyDescriptor, propertyDescriptor.type.toIrType(), Visibilities.PRIVATE
            ).also {
                it.parent = irClass
                it.initializer = irBuilder(context.irBuiltIns, it.symbol).run {
                    irExprBody(irGet(irClass.primaryConstructor!!.valueParameters[0]))
                }
            }
            irProperty.getter = symbolTable.declareSimpleFunctionWithOverrides(
                    SYNTHETIC_OFFSET, SYNTHETIC_OFFSET,
                    IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR, propertyDescriptor.getter!!
            ).also { getter ->
                getter.correspondingPropertySymbol = irProperty.symbol
                getter.parent = irClass
                getter.createDispatchReceiverParameter(IrDeclarationOrigin.INSTANCE_RECEIVER)
                getter.returnType = propertyDescriptor.getter!!.returnType!!.toIrType()
                getter.body = irBuilder(context.irBuiltIns, getter.symbol).run {
                    irExprBody(
                            irGetField(
                                    irGet(getter.dispatchReceiverParameter!!),
                                    irProperty.backingField!!
                            )
                    )
                }
            }
        }
        return irProperty
    }

    private fun provideIrClassForCEnum(descriptor: ClassDescriptor): IrClass =
            symbolTable.declareClass(
                    startOffset = SYNTHETIC_OFFSET,
                    endOffset = SYNTHETIC_OFFSET,
                    origin = IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB,
                    descriptor = descriptor
            ).also { enumIrClass ->
                context.symbolTable.withScope(descriptor) {
                    descriptor.typeConstructor.supertypes.mapTo(enumIrClass.superTypes) {
                        it.toIrType()
                    }
                    enumIrClass.createParameterDeclarations()
                    enumIrClass.addMember(createEnumPrimaryConstructor(descriptor).also {
                        it.parent = enumIrClass
                    })
                    enumIrClass.addMember(createPropertyWithBackingField(enumIrClass, "value"))
                    descriptor.enumEntries.mapTo(enumIrClass.declarations) { entryDescriptor ->
                        createEnumEntry(descriptor, entryDescriptor).also { it.parent = enumIrClass }
                    }
                    enumClassMembersGenerator.generateSpecialMembers(enumIrClass)
                    val enumCompanionObject = cEnumClassCompanionGenerator.generateEnumCompanionObject(enumIrClass)
                    enumIrClass.addChild(enumCompanionObject)
//                    val enumVarClassDescriptor = descriptor.defaultType.memberScope
//                            .getContributedClassifier(Name.identifier("Var"), NoLookupLocation.FROM_BACKEND)
//                            ?: error("No `Var` nested class!")
//                    enumIrClass.addChild(createEnumVarClass(enumVarClassDescriptor as ClassDescriptor, enumCompanionObject.functions.single { it.name.identifier == "byValue" }))
                    enumIrClass.addFakeOverridesWithProperties()
                }
            }

    private fun createEnumEntry(enumDescriptor: ClassDescriptor, entryDescriptor: ClassDescriptor): IrEnumEntry {
        return symbolTable.declareEnumEntry(
                SYNTHETIC_OFFSET, SYNTHETIC_OFFSET,
                IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB, entryDescriptor
        ).also { enumEntry ->
            enumEntry.initializerExpression = IrEnumConstructorCallImpl(
                    SYNTHETIC_OFFSET, SYNTHETIC_OFFSET,
                    type = context.irBuiltIns.unitType,
                    symbol = context.symbolTable.referenceConstructor(enumDescriptor.unsubstitutedPrimaryConstructor!!),
                    typeArgumentsCount = 0 // enums can't be generic
            ).apply {
                putValueArgument(0, extractEnumEntryValue(entryDescriptor))
            }
        }
    }

    private fun extractEnumEntryValue(entryDescriptor: ClassDescriptor): IrExpression {
        val base = FqName("kotlinx.cinterop.internal.CEnumEntryValue")
        val types = setOf(
                "Byte", "Short", "Int", "Long",
                "UByte", "UShort", "UInt", "ULong"
        )
        fun extractValue(type: String): IrExpression? {
            val value = entryDescriptor.annotations
                    .findAnnotation(base.child(Name.identifier(type)))
                    ?.allValueArguments
                    ?.getValue(Name.identifier("value"))
                    ?: return null
            return context.constantValueGenerator.generateConstantValueAsExpression(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, value)
        }

        return types.firstNotNullResult(::extractValue) ?: error("TODO")
    }

    private fun createEnumPrimaryConstructor(descriptor: ClassDescriptor): IrConstructor {
        val constructorDescriptor = descriptor.unsubstitutedPrimaryConstructor!!
        val irConstructor = context.symbolTable.declareConstructor(
                SYNTHETIC_OFFSET, SYNTHETIC_OFFSET,
                IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB, constructorDescriptor
        )
        irConstructor.valueParameters += symbolTable.declareValueParameter(
                SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, IrDeclarationOrigin.DEFINED,
                constructorDescriptor.valueParameters[0],
                constructorDescriptor.valueParameters[0].type.toIrType()).also {
            it.parent = irConstructor
        }
        val enumConstructor = context.builtIns.enum.constructors.single()
        irConstructor.body = irBuilder(context.irBuiltIns, irConstructor.symbol).irBlockBody {
            +IrEnumConstructorCallImpl(
                    startOffset, endOffset,
                    context.irBuiltIns.unitType,
                    symbolTable.referenceConstructor(enumConstructor),
                    typeArgumentsCount = 1 // kotlin.Enum<T> has a single type parameter.
            ).apply {
                putTypeArgument(0, descriptor.defaultType.toIrType())
            }
            +IrInstanceInitializerCallImpl(
                    startOffset, endOffset,
                    symbolTable.referenceClass(descriptor),
                    context.irBuiltIns.unitType
            )
        }
        irConstructor.returnType = irConstructor.descriptor.returnType.toIrType()
        return irConstructor
    }
}