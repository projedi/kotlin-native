package org.jetbrains.kotlin.backend.konan.ir.interop

import org.jetbrains.kotlin.backend.common.ir.addSimpleDelegatingConstructor
import org.jetbrains.kotlin.backend.common.ir.createDispatchReceiverParameter
import org.jetbrains.kotlin.backend.common.ir.createParameterDeclarations
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.impl.IrGetEnumValueImpl
import org.jetbrains.kotlin.ir.symbols.IrEnumEntrySymbol
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.util.irBuilder
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi2ir.generators.GeneratorContext
import org.jetbrains.kotlin.resolve.constants.StringValue

internal class CEnumClassCompanionGenerator(
        override val context: GeneratorContext
) : GeneratorContextAware {

    companion object {
        private val cEnumEntryAliasAnnonation = FqName("kotlinx.cinterop.internal.CEnumEntryAlias")
    }

    private val cEnumByValueFunctionGenerator =
            CEnumByValueFunctionGenerator(context)

    // Depends on already generated `.values()` irFunction.
    fun generateEnumCompanionObject(enumClass: IrClass): IrClass {
        val companionObjectDescriptor = enumClass.descriptor.companionObjectDescriptor!!
        val companionIrClass = symbolTable.declareClass(
                SYNTHETIC_OFFSET, SYNTHETIC_OFFSET,
                IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB,
                companionObjectDescriptor
        )
        companionIrClass.superTypes += context.irBuiltIns.anyType
        symbolTable.withScope(companionIrClass.descriptor) {
            companionIrClass.createParameterDeclarations()
            companionIrClass.addSimpleDelegatingConstructor(
                    context.irBuiltIns.anyClass.owner.constructors.first(),
                    context.irBuiltIns,
                    isPrimary = true
            )
            val valuesFunction = enumClass.functions.single { it.name.identifier == "values" }
            val byValueIrFunction = cEnumByValueFunctionGenerator
                    .generateByValueFunction(companionIrClass, valuesFunction)
            companionIrClass.addMember(byValueIrFunction)
        }
        findEntryAliases(companionObjectDescriptor)
                .map { declareEntryAliasProperty(companionIrClass, it) }
                .onEach {
                    val getter = it.getter!!
                    val entrySymbol = fundCorrespondingEnumEntrySymbol(it.descriptor, enumClass)
                    getter.body = generateAliasGetterBody(getter, entrySymbol)
                }
                .forEach(companionIrClass::addMember)
        companionIrClass.addFakeOverridesWithProperties()
        return companionIrClass
    }

    /**
     * Returns all properties in companion object that represent aliases to
     * enum entries.
     */
    private fun findEntryAliases(companionDescriptor: ClassDescriptor) =
        companionDescriptor.defaultType.memberScope.getContributedDescriptors()
                .filterIsInstance<PropertyDescriptor>()
                .filter { it.annotations.hasAnnotation(cEnumEntryAliasAnnonation) }

    private fun fundCorrespondingEnumEntrySymbol(aliasDescriptor: PropertyDescriptor, irClass: IrClass): IrEnumEntrySymbol {
        val enumEntryName = (aliasDescriptor.annotations.findAnnotation(cEnumEntryAliasAnnonation)!!
                .allValueArguments.getValue(Name.identifier("entryName")) as StringValue).value
        return irClass.declarations.filterIsInstance<IrEnumEntry>()
                .single { it.name.identifier == enumEntryName }.symbol
    }

    private fun generateAliasGetterBody(getter: IrSimpleFunction, entrySymbol: IrEnumEntrySymbol): IrBody =
            irBuilder(context.irBuiltIns, getter.symbol).irBlockBody {
                +irReturn(
                        IrGetEnumValueImpl(startOffset, endOffset, entrySymbol.owner.parentAsClass.defaultType, entrySymbol)
                )
            }

    private fun declareEntryAliasProperty(companionClass: IrClass, propertyDescriptor: PropertyDescriptor): IrProperty {
        val irProperty = symbolTable.declareProperty(
                SYNTHETIC_OFFSET, SYNTHETIC_OFFSET,
                IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB,
                propertyDescriptor)
        irProperty.getter = symbolTable.declareSimpleFunction(
                SYNTHETIC_OFFSET, SYNTHETIC_OFFSET,
                IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB,
                propertyDescriptor.getter!!
        ).also { getter ->
            getter.correspondingPropertySymbol = irProperty.symbol
            getter.parent = companionClass
            getter.createDispatchReceiverParameter()
            getter.returnType = propertyDescriptor.getter!!.returnType!!.toIrType()
        }
        return irProperty
    }
}