package org.jetbrains.kotlin.backend.konan.ir.interop

import org.jetbrains.kotlin.backend.konan.InteropBuiltIns
import org.jetbrains.kotlin.backend.konan.descriptors.findPackage
import org.jetbrains.kotlin.backend.konan.descriptors.isFromInteropLibrary
import org.jetbrains.kotlin.backend.konan.ir.KonanSymbols
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.PackageFragmentDescriptor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationContainer
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.impl.IrFileImpl
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.util.IrProvider
import org.jetbrains.kotlin.ir.util.NaiveSourceBasedFileEntryImpl
import org.jetbrains.kotlin.ir.util.SymbolTable
import org.jetbrains.kotlin.ir.util.referenceFunction
import org.jetbrains.kotlin.psi2ir.generators.GeneratorContext
import org.jetbrains.kotlin.resolve.descriptorUtil.module

// TODO: Consider unification with other providers.
// TODO: Move class generation to the separate class.
// TODO: Explain why we use a different approach for CEnum
internal class IrProviderForCEnumStubs(
        context: GeneratorContext,
        private val interopBuiltIns: InteropBuiltIns,
        konanSymbols: KonanSymbols
) : IrProvider {

    private val symbolTable: SymbolTable = context.symbolTable

    private val filesMap = mutableMapOf<PackageFragmentDescriptor, IrFile>()

    private val cEnumClassGenerator = CEnumClassGenerator(context, konanSymbols)

    var module: IrModuleFragment? = null
        set(value) {
            if (value == null)
                error("Provide a valid non-null module")
            if (field != null)
                error("Module has already been set")
            field = value
            value.files += filesMap.values
        }

    override fun getDeclaration(symbol: IrSymbol): IrDeclaration? {
        if (!symbol.descriptor.module.isFromInteropLibrary()) return null
        val enumClassDescriptor = symbol.findCEnumDescriptor(interopBuiltIns)
                ?: return null
        val irClass = cEnumClassGenerator.findAndGenerateCEnum(enumClassDescriptor, irParentFor(enumClassDescriptor))
        return when (symbol) {
            is IrClassSymbol -> symbolTable.referenceClass(symbol.descriptor).owner
            is IrEnumEntrySymbol -> symbolTable.referenceEnumEntry(symbol.descriptor).owner
            is IrFunctionSymbol -> symbolTable.referenceFunction(symbol.descriptor).owner
            is IrPropertySymbol -> symbolTable.referenceProperty(symbol.descriptor).owner
            else -> error(symbol)
        }
    }

    private fun irParentFor(descriptor: ClassDescriptor): IrDeclarationContainer {
        val packageFragmentDescriptor = descriptor.findPackage()
        return filesMap.getOrPut(packageFragmentDescriptor) {
            IrFileImpl(NaiveSourceBasedFileEntryImpl("CEnums"), packageFragmentDescriptor).also {
                this@IrProviderForCEnumStubs.module?.files?.add(it)
            }
        }
    }
}