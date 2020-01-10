package org.jetbrains.kotlin.backend.konan.ir.interop

import org.jetbrains.kotlin.backend.common.ir.addSimpleDelegatingConstructor
import org.jetbrains.kotlin.backend.common.ir.createParameterDeclarations
import org.jetbrains.kotlin.backend.konan.ir.KonanSymbols
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.addMember
import org.jetbrains.kotlin.ir.util.SYNTHETIC_OFFSET
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.withScope
import org.jetbrains.kotlin.psi2ir.generators.GeneratorContext

internal class CEnumClassCompanionGenerator(
        override val context: GeneratorContext
) : GeneratorContextAware {

    private val cEnumByValueFunctionGenerator =
            CEnumByValueFunctionGenerator(context)

    // Depends on already generated `.values()` irFunction.
    fun generateEnumCompanionObject(enumClass: IrClass): IrClass {
        val companionIrClass = symbolTable.declareClass(
                SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB,
                enumClass.descriptor.companionObjectDescriptor!!
        )
        companionIrClass.superTypes += context.irBuiltIns.anyType
        context.symbolTable.withScope(companionIrClass.descriptor) {
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
        companionIrClass.addFakeOverridesWithProperties()
        return companionIrClass
    }
}