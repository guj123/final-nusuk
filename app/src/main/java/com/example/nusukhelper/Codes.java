package com.example.nusukhelper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

/** All file logic: final.txt -> used.txt. Progress lives in the files, so it always resumes. */
public final class Codes {
    public static final String DEFAULT_DIR = "/storage/emulated/0/Download/C";
    private static File FINAL, USED, BAK, LOG;
    static { setDir(DEFAULT_DIR); }

    public static synchronized void setDir(String dir) {
        FINAL = new File(dir, "final.txt");
        USED = new File(dir, "used.txt");
        BAK = new File(dir, "final.txt.bak");
        LOG = new File(dir, "results.txt");
    }
    public static synchronized boolean hasFinal() { return FINAL.exists(); }
    private static final List<String> pending = new ArrayList<>();

    private Codes() { }

    public static synchronized void load() throws IOException {
        pending.clear();
        Set<String> used = new HashSet<>(read(USED));
        if (!BAK.exists() && FINAL.exists()) write(BAK, read(FINAL), false);
        Set<String> seen = new HashSet<>();
        for (String line : read(FINAL)) {
            String c = line.trim();
            if (c.matches("NG-[A-Za-z0-9]+") && !used.contains(c) && seen.add(c)) pending.add(c);
        }
        write(FINAL, pending, false);
    }

    public static synchronized String current() { return pending.isEmpty() ? null : pending.get(0); }
    public static synchronized int size() { return pending.size(); }

    /** Log the current code to used.txt first, then remove it from final.txt. */
    public static synchronized boolean markUsed(String status) {
        if (pending.isEmpty()) return false;
        String c = pending.get(0);
        try {
            write(USED, Collections.singletonList(c), true);
            pending.remove(0);
            write(FINAL, pending, false);
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            write(LOG, Collections.singletonList(ts + " " + status + " " + c), true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static synchronized void skip() {
        if (pending.size() < 2) return;
        pending.add(pending.remove(0));
        try { write(FINAL, pending, false); } catch (IOException ignored) { }
    }

    private static List<String> read(File f) throws IOException {
        List<String> out = new ArrayList<>();
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String s;
            while ((s = r.readLine()) != null) out.add(s.replace("\r", ""));
        }
        return out;
    }

    private static void write(File f, List<String> lines, boolean append) throws IOException {
        try (FileWriter w = new FileWriter(f, append)) {
            for (String s : lines) w.write(s + "\n");
        }
    }
}
