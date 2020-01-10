package org.jetbrains.kotlin.backend.konan.ir.interop

import org.jetbrains.kotlin.backend.common.ir.createDispatchReceiverParameter
import org.jetbrains.kotlin.backend.konan.ir.KonanSymbols
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.types.isInt
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.util.irCall
import org.jetbrains.kotlin.psi2ir.generators.GeneratorContext
import org.jetbrains.kotlin.util.OperatorNameConventions

/**
 * Generate IR for function that returns appropriate enum entry for the provided integral value.
 */
internal class CEnumByValueFunctionGenerator(
        override val context: GeneratorContext,
        private val konanSymbols: KonanSymbols
) : GeneratorContextAware {
    fun generateByValueFunction(companionIrClass: IrClass, valuesIrFunction: IrSimpleFunction): IrFunction {
        val byValueFunctionDescriptor = companionIrClass.descriptor.findDeclarationByName<FunctionDescriptor>("byValue")!!
        val byValueIrFunction = symbolTable.declareSimpleFunction(
                SYNTHETIC_OFFSET, SYNTHETIC_OFFSET,
                IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB, byValueFunctionDescriptor
        )
        byValueIrFunction.parent = companionIrClass
        byValueIrFunction.createDispatchReceiverParameter(IrDeclarationOrigin.INSTANCE_RECEIVER)
        val valueParameterDescriptor = byValueFunctionDescriptor.valueParameters[0]
        val irValueParameter = symbolTable.declareValueParameter(
                SYNTHETIC_OFFSET, SYNTHETIC_OFFSET, IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB,
                valueParameterDescriptor, valueParameterDescriptor.type.toIrType()
        )
        byValueIrFunction.valueParameters += irValueParameter
        byValueIrFunction.returnType = byValueFunctionDescriptor.returnType!!.toIrType()
        // val values: Array<E> = values()
        // val i: Int = 0
        // val size: Int = values.size
        // while (i < size) {
        //      val entry: E = values[i]
        //      val entryValue = entry.value
        //      if (entryValue == arg) {
        //          return entry
        //      }
        //      i++
        // }
        // throw IllegalStateException
        byValueIrFunction.body = irBuilder(context.irBuiltIns, byValueIrFunction.symbol).irBlockBody {
            val values = irTemporaryVar(irCall(valuesIrFunction))
            val inductionVariable = irTemporaryVar(irInt(0))
            val valuesSize = irCall(values.type.getClass()!!.getPropertyGetter("size")!!.owner).also { irCall ->
                irCall.dispatchReceiver = irGet(values)
            }
            val getElementFn = values.type.getClass()!!.functions.single {
                it.name == OperatorNameConventions.GET &&
                        it.valueParameters.size == 1 &&
                        it.valueParameters[0].type.isInt()
            }
            val plusFun = inductionVariable.type.getClass()!!.functions.single {
                it.name == OperatorNameConventions.PLUS &&
                        it.valueParameters.size == 1 &&
                        it.valueParameters[0].type == context.irBuiltIns.intType
            }
            val lessFunctionSymbol = context.irBuiltIns.lessFunByOperandType.getValue(context.irBuiltIns.intClass)
            +irWhile().also { loop ->
                loop.condition = irCall(lessFunctionSymbol).also { irCall ->
                    irCall.putValueArgument(0, irGet(inductionVariable))
                    irCall.putValueArgument(1, valuesSize)
                }
                loop.body = irBlock {
                    val untypedEntry = irCall(getElementFn).also { irCall ->
                        irCall.dispatchReceiver = irGet(values)
                        irCall.putValueArgument(0, irGet(inductionVariable))
                    }
                    val entry = irTemporaryVar(irImplicitCast(untypedEntry, byValueIrFunction.returnType))
                    val valueGetter = entry.type.getClass()!!.getPropertyGetter("value")!!
                    val entryValue = irTemporaryVar(irGet(irValueParameter.type, irGet(entry), valueGetter))
                    +irIfThenElse(
                            type = context.irBuiltIns.unitType,
                            condition = irEquals(irGet(entryValue), irGet(irValueParameter)),
                            thenPart = irReturn(irGet(entry)),
                            elsePart = irSetVar(inductionVariable, irCallOp(plusFun, irGet(inductionVariable), irInt(1)))
                    )
                }
            }
            +irCall(konanSymbols.throwIllegalStateException)
        }
        return byValueIrFunction
    }
}