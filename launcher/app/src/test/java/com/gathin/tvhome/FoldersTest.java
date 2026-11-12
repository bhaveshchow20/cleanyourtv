package com.gathin.tvhome;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Home's folders as saved and as edited (Folders): a bug here hides apps or shows one twice. */
public class FoldersTest {

    private static final String NETFLIX = "com.netflix.ninja/.MainActivity", YOUTUBE = "com.google.android.youtube.tv/x.Y",
            HULU = "com.hulu.livingroomplus/.Main";
    private static final String FOLDER_KEY = "com.gathin.tvhome/folder:abc";

    @Test public void aRoundTripKeepsIdsNamesAndOrder() {
        List<Folders.Folder> folders = new ArrayList<>();
        Folders.Folder f = Folders.join(folders, NETFLIX, YOUTUBE, "abc");
        Folders.rename(folders, "abc", "Kids \"&\" <Music>");
        List<Folders.Folder> back = Folders.parse(Folders.toJson(folders));
        assertEquals(1, back.size());
        assertEquals("abc", back.get(0).id);
        assertEquals("Kids \"&\" <Music>", back.get(0).name);
        assertEquals(Arrays.asList(YOUTUBE, NETFLIX), back.get(0).apps);
        assertSame(f, folders.get(0));
    }

    @Test public void joiningAnAppMakesAFolderInTheTargetsPlaceWithTheTargetFirst() {
        List<Folders.Folder> folders = new ArrayList<>();
        Folders.Folder f = Folders.join(folders, NETFLIX, YOUTUBE, "abc");
        assertNotNull(f);
        assertEquals(Arrays.asList(YOUTUBE, NETFLIX), f.apps);
        assertEquals("", f.name);
    }

    @Test public void joiningAFolderAddsToItOnce() {
        List<Folders.Folder> folders = new ArrayList<>();
        Folders.join(folders, NETFLIX, YOUTUBE, "abc");
        Folders.Folder f = Folders.join(folders, HULU, FOLDER_KEY, "unused");
        assertEquals(Arrays.asList(YOUTUBE, NETFLIX, HULU), f.apps);
        Folders.join(folders, HULU, FOLDER_KEY, "unused");
        assertEquals("an app is never in a folder twice", 3, f.apps.size());
        assertEquals(1, folders.size());
    }

    @Test public void foldersDoNotNestAndAnAppCannotJoinItself() {
        List<Folders.Folder> folders = new ArrayList<>();
        assertNull(Folders.join(folders, FOLDER_KEY, NETFLIX, "x"));
        assertNull(Folders.join(folders, NETFLIX, NETFLIX, "x"));
        assertNull(Folders.join(folders, NETFLIX, "com.gathin.tvhome/folder:gone", "x"));
        assertNull(Folders.join(folders, null, NETFLIX, "x"));
        assertTrue(folders.isEmpty());
    }

    @Test public void renamingTrimsAndABlankNameGoesBackToTheDefault() {
        List<Folders.Folder> folders = new ArrayList<>();
        Folders.join(folders, NETFLIX, YOUTUBE, "abc");
        assertTrue(Folders.rename(folders, "abc", "  Movies  "));
        assertEquals("Movies", folders.get(0).name);
        assertTrue(Folders.rename(folders, "abc", "   "));
        assertEquals("", folders.get(0).name);
        assertFalse(Folders.rename(folders, "nope", "x"));
    }

    @Test public void removingTheLastAppDeletesTheFolder() {
        List<Folders.Folder> folders = new ArrayList<>();
        Folders.join(folders, NETFLIX, YOUTUBE, "abc");
        assertFalse(Folders.removeApp(folders, "abc", NETFLIX));
        assertEquals(1, folders.size());
        assertTrue(Folders.removeApp(folders, "abc", YOUTUBE));
        assertTrue(folders.isEmpty());
        assertEquals("[]", Folders.toJson(folders));
        assertFalse(Folders.removeApp(folders, "abc", YOUTUBE));
    }

    @Test public void corruptJsonGivesNoFoldersInsteadOfACrash() {
        for (String bad : new String[]{null, "", "{", "not json", "{\"id\":\"a\"}", "[1,2,3]", "[{\"apps\":[\"x\"]}]",
                "[{\"id\":\"a\",\"apps\":\"x\"}]", "[{\"id\":\"a\",\"apps\":[]}]"}) {
            assertTrue(String.valueOf(bad), Folders.parse(bad).isEmpty());
        }
    }

    @Test public void anAppSavedInTwoFoldersShowsOnlyInTheFirst() {
        String json = "[{\"id\":\"a\",\"name\":\"A\",\"apps\":[\"" + NETFLIX + "\",\"" + NETFLIX + "\",\"\"]},"
                + "{\"id\":\"b\",\"name\":\"B\",\"apps\":[\"" + NETFLIX + "\",\"" + HULU + "\",\"" + FOLDER_KEY + "\"]},"
                + "{\"id\":\"a\",\"name\":\"dup\",\"apps\":[\"" + YOUTUBE + "\"]}]";
        List<Folders.Folder> folders = Folders.parse(json);
        assertEquals(2, folders.size());
        assertEquals(Arrays.asList(NETFLIX), folders.get(0).apps);
        assertEquals("a folder key inside a folder is dropped", Arrays.asList(HULU), folders.get(1).apps);
    }

    @Test public void folderKeysAndAppKeysAreToldApart() {
        assertEquals("abc", Folders.idOf(FOLDER_KEY));
        assertNull(Folders.idOf(NETFLIX));
        assertNull(Folders.idOf(null));
    }
}
