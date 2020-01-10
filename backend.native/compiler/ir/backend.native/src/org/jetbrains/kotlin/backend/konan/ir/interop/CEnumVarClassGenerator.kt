package org.jetbrains.kotlin.backend.konan.ir.interop

import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.util.SYNTHETIC_OFFSET
import org.jetbrains.kotlin.psi2ir.generators.GeneratorContext

internal class CEnumVarClassGenerator(override val context: GeneratorContext) : GeneratorContextAware {
    fun createEnumVarClass(
            enumVarClassDescriptor: ClassDescriptor,
            byValueIrFunction: IrFunction
    ): IrClass =
            symbolTable.declareClass(
                    startOffset = SYNTHETIC_OFFSET,
                    endOffset = SYNTHETIC_OFFSET,
                    origin = IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB,
                    descriptor = enumVarClassDescriptor
            ).also { enumVarIrClass ->
                //                addPropertyWithBackingField(enumVarIrClass, "value") { irProperty ->
//                    context.symbolTable.withScope(irProperty.descriptor) {
//                        val getter = irProperty.addGetter {
//                            returnType = irProperty.backingField!!.type
//                        }
//                        val setter = irProperty.addSetter {
//
//                        }
//                        val setterParameter = symbolTable.declareValueParameter(
//                                SYNTHETIC_OFFSET, SYNTHETIC_OFFSET,
//                                IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB,
//                                irProperty.descriptor.setter!!.valueParameters[0],
//                                irProperty.backingField!!.type)
//                        val call = irCall(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, byValueIrFunction, emptyList()).apply {
//                            putValueArgument(0, IrGetValueImpl(SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, setterParameter.symbol))
//                        }
//                        setter.body = IrExpressionBodyImpl(call)
//                    }
//                }
            }
}