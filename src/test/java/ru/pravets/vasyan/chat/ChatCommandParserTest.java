package ru.pravets.vasyan.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static ru.pravets.vasyan.chat.ChatCommandParser.isAllCommand;
import static ru.pravets.vasyan.chat.ChatCommandParser.isStayCommand;
import static ru.pravets.vasyan.chat.ChatCommandParser.normalize;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatCommandParserTest {

    // ---- isAllCommand: English ----

    @Test
    void englishAllPrefixes() {
        assertTrue(isAllCommand(normalize("all teleport to me")));
        assertTrue(isAllCommand(normalize("all vasyans come here")));
        assertTrue(isAllCommand(normalize("everyone go mine")));
        assertTrue(isAllCommand(normalize("everybody gather")));
    }

    @Test
    void russianAllPrefixes() {
        assertTrue(isAllCommand(normalize("все телепортируйтесь ко мне")));
        assertTrue(isAllCommand(normalize("всем ко мне")));
        assertTrue(isAllCommand(normalize("все боты сюда")));
        assertTrue(isAllCommand(normalize("Все идите копать")));
    }

    @Test
    void nonAllCommandsAreRejected() {
        assertFalse(isAllCommand(normalize("alex come to me")));
        assertFalse(isAllCommand(normalize("build a house")));
        assertFalse(isAllCommand(normalize("построй дом")));
        assertFalse(isAllCommand(normalize("stay here")));
        assertFalse(isAllCommand(normalize("вселенная опасна"))); // "все" без пробела не сработает
    }

    // ---- isStayCommand ----

    @Test
    void englishStayWords() {
        assertTrue(isStayCommand(normalize("stay")));
        assertTrue(isStayCommand(normalize("stay here")));
        assertTrue(isStayCommand(normalize("stop")));
        assertTrue(isStayCommand(normalize("wait for me")));
        assertTrue(isStayCommand(normalize("freeze")));
    }

    @Test
    void russianStayWords() {
        assertTrue(isStayCommand(normalize("стой")));
        assertTrue(isStayCommand(normalize("стой на месте")));
        assertTrue(isStayCommand(normalize("замри")));
        assertTrue(isStayCommand(normalize("остановись")));
        assertTrue(isStayCommand(normalize("стоп")));
        assertTrue(isStayCommand(normalize("стоять")));
        assertTrue(isStayCommand(normalize("жди меня")));
    }

    @Test
    void fillCommands() {
        assertTrue(ChatCommandParser.isFillCommand(normalize("добудь дерево до полного инвентаря")));
        assertTrue(ChatCommandParser.isFillCommand(normalize("заполни инвентарь деревом")));
        assertTrue(ChatCommandParser.isFillCommand(normalize("fill inventory with wood")));
        assertTrue(ChatCommandParser.isFillCommand(normalize("gather until full")));
        assertFalse(ChatCommandParser.isFillCommand(normalize("добудь 50 дерева")));
        assertFalse(ChatCommandParser.isFillCommand(normalize("stay")));
        assertFalse(ChatCommandParser.isFillCommand(null));
    }

    @Test
    void stackCommands() {
        assertTrue(ChatCommandParser.isStackCommand(normalize("добудь стак дерева")));
        assertTrue(ChatCommandParser.isStackCommand(normalize("a stack of oak logs")));
        assertFalse(ChatCommandParser.isStackCommand(normalize("добудь 50 дерева")));
        assertFalse(ChatCommandParser.isStackCommand(normalize("stay")));
    }

    @Test
    void woodCommands() {
        assertTrue(ChatCommandParser.isWoodCommand(normalize("добудь дерева")));
        assertTrue(ChatCommandParser.isWoodCommand(normalize("добудь 50 брёвен")));
        assertTrue(ChatCommandParser.isWoodCommand(normalize("gather wood")));
        assertTrue(ChatCommandParser.isWoodCommand(normalize("chop trees")));
        assertFalse(ChatCommandParser.isWoodCommand(normalize("добудь железо")));
        assertFalse(ChatCommandParser.isWoodCommand(normalize("mine iron ore")));
    }

    @Test
    void nonStayCommandsAreRejected() {
        assertFalse(isStayCommand(normalize("mine iron")));
        assertFalse(isStayCommand(normalize("иди копай")));
        assertFalse(isStayCommand(normalize("teleport to me")));
        assertFalse(isStayCommand(normalize("")));
    }

    // ---- isLookCommand ----

    @Test
    void lookCommands() {
        assertTrue(ChatCommandParser.isLookCommand(normalize("что ты видишь")));
        assertTrue(ChatCommandParser.isLookCommand(normalize("что видишь")));
        assertTrue(ChatCommandParser.isLookCommand(normalize("что видишь?")));
        assertTrue(ChatCommandParser.isLookCommand(normalize("what do you see")));
        assertTrue(ChatCommandParser.isLookCommand(normalize("look around")));
        assertTrue(ChatCommandParser.isLookCommand(normalize("look")));
        assertFalse(ChatCommandParser.isLookCommand(normalize("mine iron")));
        assertFalse(ChatCommandParser.isLookCommand(normalize("lookout")));
        assertFalse(ChatCommandParser.isLookCommand(normalize("look for diamonds")));
        assertFalse(ChatCommandParser.isLookCommand(normalize("")));
    }

    // ---- parseGoToCommand ----

    @Test
    void goToCommands() {
        assertArrayEquals(new int[]{100, 64, -200},
            ChatCommandParser.parseGoToCommand(normalize("иди к 100 64 -200")));
        assertArrayEquals(new int[]{0, 64, 0},
            ChatCommandParser.parseGoToCommand(normalize("go to 0 64 0")));
        assertArrayEquals(new int[]{-5, 70, 12},
            ChatCommandParser.parseGoToCommand(normalize("подойди к -5 70 12")));
        assertArrayEquals(new int[]{10, 65, 20},
            ChatCommandParser.parseGoToCommand(normalize("bob иди к 10 65 20")));

        // Marker without coordinates stays on the LLM path ("иди ко мне").
        assertNull(ChatCommandParser.parseGoToCommand(normalize("иди ко мне")));
        assertNull(ChatCommandParser.parseGoToCommand(normalize("иди к игроку")));
        // Not a goto at all.
        assertNull(ChatCommandParser.parseGoToCommand(normalize("gather 50 wood")));
        assertNull(ChatCommandParser.parseGoToCommand(normalize("")));
        assertNull(ChatCommandParser.parseGoToCommand(null));
    }
}
