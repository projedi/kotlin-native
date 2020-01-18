/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package org.jetbrains.kotlin.backend.konan.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.lower.IrBuildingTransformer
import org.jetbrains.kotlin.backend.common.lower.at
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.types.isString
import org.jetbrains.kotlin.ir.util.fqNameForIrSerialization
import org.jetbrains.kotlin.ir.util.irCall
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

internal class CompileTimeEvaluateLowering(val context: Context): FileLoweringPass {

    override fun lower(irFile: IrFile) {
        irFile.transformChildrenVoid(object: IrBuildingTransformer(context) {
            override fun visitCall(expression: IrCall): IrExpression {
                expression.transformChildrenVoid(this)

                val callee = expression.symbol.owner
                // TODO: refer functions more reliably.
                val functionName = callee.fqNameForIrSerialization.asString()
                val parametersCount = callee.valueParameters.size
                when {
                    functionName == "kotlin.collections.listOf" && parametersCount == 1 ->
                        return transformListOf(expression)
                    else -> return expression
                }
            }

            fun transformListOf(expression: IrCall) : IrExpression {
                // The function is kotlin.collections.listOf<T>(vararg args: T).

                val elementsArr = expression.getValueArgument(0) as? IrVararg
                    ?: return expression

                if (elementsArr.elements.any { it is IrSpreadElement }
                        || !elementsArr.elements.all { it is IrConst<*> && it.type.isString() })
                    return expression

                builder.at(expression)

                val typeArgument = expression.getTypeArgument(0)!!
                return builder.irCall(context.ir.symbols.listOfInternal.owner, listOf(typeArgument)).apply {
                    putValueArgument(0, elementsArr)
                }
            }
        })
    }
}
