package com.example.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class AppTest {
    @Test
    void helloMessage_isCorrect() {
        String output = "Hello, Java starter!";
        assertEquals("Hello, Java starter!", output);
    }
}
