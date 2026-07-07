package com.docwallet.cli

import com.docwallet.cli.bench.benchCommand
import com.docwallet.cli.crypto.cryptoCommand
import com.docwallet.cli.search.searchCommand
import com.docwallet.cli.vault.vaultCommand
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands

class CliApp : CliktCommand(name = "docwallet", help = "DocWallet CLI tester") {
    override fun run() = Unit
}

fun main(args: Array<String>) = CliApp()
    .subcommands(cryptoCommand(), vaultCommand(), searchCommand(), benchCommand())
    .main(args)
