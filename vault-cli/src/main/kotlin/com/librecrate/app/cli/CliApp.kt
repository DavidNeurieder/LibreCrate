package com.librecrate.app.cli

import com.librecrate.app.cli.bench.benchCommand
import com.librecrate.app.cli.crypto.cryptoCommand
import com.librecrate.app.cli.document.documentCommand
import com.librecrate.app.cli.search.searchCommand
import com.librecrate.app.cli.vault.vaultCommand
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands

class CliApp : CliktCommand(name = "librecrate", help = "LibreCrate CLI") {
    override fun run() = Unit
}

fun main(args: Array<String>) = CliApp()
    .subcommands(
        cryptoCommand(),
        vaultCommand(),
        documentCommand(),
        searchCommand(),
        benchCommand(),
    )
    .main(args)
