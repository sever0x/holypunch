package io.github.sever0x.holypunch.client;

import io.github.sever0x.holypunch.client.cli.ReceiveCommand;
import io.github.sever0x.holypunch.client.cli.SendCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.nio.charset.StandardCharsets;

@Command(
        name = "holypunch",
        subcommands = {
                SendCommand.class,
                ReceiveCommand.class,
                CommandLine.HelpCommand.class
        },
        mixinStandardHelpOptions = true,
        version = "0.1.0",
        description = "P2P file transfer with NAT hole punching and relay fallback."
)
public class HolypunchMain implements Runnable {

    public static void main(String[] args) {
        // Auto-set UTF-8 so box-drawing and progress bar characters render correctly.
        // On Windows CMD, also change the active code page (chcp 65001).
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            try { new ProcessBuilder("cmd", "/c", "chcp", "65001").start(); }
            catch (Exception ignored) {}
        }
        try {
            System.setOut(new java.io.PrintStream(System.out, true, StandardCharsets.UTF_8));
            System.setErr(new java.io.PrintStream(System.err, true, StandardCharsets.UTF_8));
        } catch (Exception ignored) {}

        int exit = new CommandLine(new HolypunchMain()).execute(args);
        System.exit(exit);
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}
