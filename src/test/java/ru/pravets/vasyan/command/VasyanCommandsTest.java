package ru.pravets.vasyan.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class VasyanCommandsTest {

    private static final CommandSourceStack SOURCE = mock(CommandSourceStack.class);
    private static CommandDispatcher<CommandSourceStack> dispatcher;

    @BeforeAll
    static void setUp() {
        dispatcher = new CommandDispatcher<>();
        VasyanCommands.register(dispatcher);
    }

    @Test
    void parsesDumpCommand() {
        assertParses("vasyan dump Bob");
        assertParses("vasyan dump Bob with-prompt");
    }

    @Test
    void parsesLookCommand() {
        assertParses("vasyan look Bob");
    }

    private static void assertParses(String command) {
        ParseResults<CommandSourceStack> results = dispatcher.parse(command, SOURCE);
        assertNotNull(results.getContext().getCommand(),
            "Command should parse successfully: " + command);
        assertFalse(results.getReader().canRead(),
            "Command should consume all input: " + command);
    }
}
