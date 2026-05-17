package io.github.sever0x.holypunch.client;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = "holypunch",
        subcommands = {CommandLine.HelpCommand.class},
        mixinStandardHelpOptions = true,
        version = "0.1.0",
        description = "P2P file transfer with NAT hole punching"
)
public class HolypunchMain implements Runnable {

    public static void main(String[] args) {
        int exit = new CommandLine(new HolypunchMain()).execute(args);
        System.exit(exit);
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}
