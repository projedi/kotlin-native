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
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
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
                // TODO: refer to functions more reliably.
                val functionName = callee.fqNameForIrSerialization.asString()
                val parametersCount = callee.valueParameters.size
                val hasDispatchReceiver = callee.dispatchReceiverParameter != null
                val hasExtensionReceiver = callee.extensionReceiverParameter != null
                val hasReceiver = hasDispatchReceiver || hasExtensionReceiver
                when {
                    functionName == "kotlin.collections.listOf" && parametersCount == 1 && !hasReceiver ->
                        return transformListOf(expression)
                    functionName == "kotlin.to" && parametersCount == 1 && hasExtensionReceiver ->
                        return transformTo(expression)
                    functionName == "kotlin.collections.mapOf" && parametersCount == 1 && !hasReceiver ->
                        return transformMapOf(expression)
                    functionName == "kotlin.collections.setOf" && parametersCount == 1 && !hasReceiver ->
                        return transformSetOf(expression)
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

            fun transformTo(expression: IrCall) : IrExpression {
                val lhs = expression.extensionReceiver ?: return expression
                val rhs = expression.getValueArgument(0) ?: return expression
                return IrConstructorCallImpl.fromSymbolOwner(expression.startOffset, expression.endOffset, expression.type, context.ir.symbols.pairConstructor).apply {
                    putValueArgument(0, lhs)
                    putTypeArgument(0, lhs.type)
                    putValueArgument(1, rhs)
                    putTypeArgument(1, rhs.type)
                }
            }

            fun transformMapOf(expression: IrCall) : IrExpression {
                // The function is kotlin.collections.mapOf<K, V>(vararg pairs: Pair<K, V>).

                val elementsArr = expression.getValueArgument(0) as? IrVararg
                    ?: return expression

                val isPairOfConsts = fun(element: IrVarargElement): Boolean {
                    if (element !is IrConstructorCall)
                        return false
                    if (element.valueArgumentsCount != 2)
                        return false
                    val first = element.getValueArgument(0)
                    if (first !is IrConst<*>)
                        return false
                    // TODO: Can also be done for other types
                    if (first.kind != IrConstKind.String)
                        return false
                    val second = element.getValueArgument(1)
                    if (second !is IrConst<*>)
                        return false
                    // TODO: Can also be done for other types
                    if (second.kind != IrConstKind.String)
                        return false
                    return true
                }

                if (elementsArr.elements.any { it is IrSpreadElement }
                        || !elementsArr.elements.all(isPairOfConsts))
                    return expression

                builder.at(expression)

                val typeArgument = expression.getTypeArgument(0)!!
                return builder.irCall(context.ir.symbols.mapOfInternal.owner, listOf(typeArgument)).apply {
                    putValueArgument(0, elementsArr)
                }
            }

            fun transformSetOf(expression: IrCall) : IrExpression {
                // The function is kotlin.collections.setOf<T>(vararg elements: T).

                val elementsArr = expression.getValueArgument(0) as? IrVararg
                    ?: return expression

                // TODO: Can also be done for other types
                if (elementsArr.elements.any { it is IrSpreadElement }
                        || !elementsArr.elements.all { it is IrConst<*> && it.type.isString() })
                    return expression

                builder.at(expression)

                val typeArgument = expression.getTypeArgument(0)!!
                return builder.irCall(context.ir.symbols.setOfInternal.owner, listOf(typeArgument)).apply {
                    putValueArgument(0, elementsArr)
                }
            }
        })
    }
}
