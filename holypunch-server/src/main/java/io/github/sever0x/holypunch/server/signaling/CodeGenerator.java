package io.github.sever0x.holypunch.server.signaling;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;

@Component
public class CodeGenerator {

    private final List<String> words;
    private final SecureRandom random = new SecureRandom();

    public CodeGenerator() {
        try (InputStream in = getClass().getResourceAsStream("/words.txt")) {
            if (in == null) throw new IllegalStateException("words.txt not found in classpath");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                words = reader.lines()
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load word list", e);
        }
        if (words.size() < 4) throw new IllegalStateException("Word list too short");
    }

    public String generate() {
        int size = words.size();
        return words.get(random.nextInt(size)) + "-" +
               words.get(random.nextInt(size)) + "-" +
               words.get(random.nextInt(size)) + "-" +
               words.get(random.nextInt(size));
    }
}
