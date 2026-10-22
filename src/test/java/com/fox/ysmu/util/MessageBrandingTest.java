package com.fox.ysmu.util;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.debug.MolangDebugSnapshot;

class MessageBrandingTest {

    private static String plain(String text) {
        return text.replace("\\u00a7", "§").replaceAll("§[0-9a-fk-orA-FK-OR]", "");
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    @Test
    void ownSourceAndLanguagePrefixesDoNotUseTheOldBrand() throws Exception {
        Pattern legacy = Pattern.compile("\\[YSM(?:\\]|[- ])");
        // Only our implementation and language bundles: do not rename YSM product/format
        // references or strings authored inside bundled model assets.
        for (Path root : new Path[]{Paths.get("src/main/java/com/fox/ysmu"),
            Paths.get("src/main/resources/assets/ysmu/lang")}) {
            try (Stream<Path> paths = Files.walk(root)) {
                Iterator<Path> files = paths.filter(Files::isRegularFile).filter(p ->
                    p.toString().endsWith(".java") || p.toString().endsWith(".lang")).iterator();
                while (files.hasNext()) {
                    Path file = files.next();
                    assertFalse(legacy.matcher(plain(read(file))).find(), "Legacy display prefix in " + file);
                }
            }
        }
    }

    @Test
    void debugAndLocalizedCommandPrefixesShareTheYsmuBrand() throws Exception {
        assertEquals("[YSMU]", plain(MolangDebugSnapshot.CHAT_PREFIX));
        for (String locale : new String[]{"en_US", "zh_CN"}) {
            Properties language = new Properties();
            language.load(new StringReader(read(Paths.get("src/main/resources/assets/ysmu/lang/" + locale + ".lang"))));
            for (String key : new String[]{"message.yes_steve_model.model.debug_animation.true",
                "message.yes_steve_model.model.debug_animation.false", "commands.yes_steve_model.export.success",
                "commands.yes_steve_model.export.not_exist"}) {
                assertNotNull(language.getProperty(key), locale + ": " + key);
                assertTrue(plain(language.getProperty(key)).startsWith("[YSMU]"), locale + ": " + key);
            }
        }
    }
}
