package com.godot.game;

import android.app.Application;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, application = Application.class)
public class SteamWorkshopCollectionsParsingTest {
	@Test public void collectionDetailsResponseExtractsChildrenFileTypesAndTitle() throws Exception {
		String payload = new JSONObject()
			.put("response", new JSONObject()
				.put("result", 1)
				.put("resultcount", 1)
				.put("publishedfiledetails", new JSONArray().put(new JSONObject()
					.put("publishedfileid", "3333")
					.put("result", 1)
					.put("title", "Fixture 合集")
					.put("children", new JSONArray()
						.put(new JSONObject().put("publishedfileid", "90001").put("file_type", 0))
						.put(new JSONObject().put("publishedfileid", "90002").put("file_type", 2))
						.put(new JSONObject().put("publishedfileid", "0").put("file_type", 0))
						.put(new JSONObject().put("publishedfileid", "not-a-number").put("file_type", 0))
						.put(new JSONObject().put("publishedfileid", "90003").put("file_type", 0))))))
			.toString();

		Object parsed = SteamWorkshopCollections.parseSteamWorkshopCollectionDetailsResponse(payload, "3333",
			SteamWorkshopCollections.SOURCE_WEBAPI);
		assertNotNull(parsed);
		assertEquals("Fixture 合集", field(parsed, "title"));
		assertEquals(SteamWorkshopCollections.SOURCE_WEBAPI, field(parsed, "source"));
		List<?> children = children(parsed);
		assertEquals(3, children.size());
		assertEquals("90001", childField(children.get(0), "publishedFileId"));
		assertEquals(0, childFieldInt(children.get(0), "fileType"));
		assertEquals("90002", childField(children.get(1), "publishedFileId"));
		assertEquals(2, childFieldInt(children.get(1), "fileType"));
	}

	@Test public void collectionDetailsResponseWithoutMatchingEntryReturnsNull() throws Exception {
		String payload = new JSONObject()
			.put("response", new JSONObject()
				.put("publishedfiledetails", new JSONArray().put(new JSONObject()
					.put("publishedfileid", "4444")
					.put("result", 1))))
			.toString();
		assertNull(SteamWorkshopCollections.parseSteamWorkshopCollectionDetailsResponse(payload, "3333",
			SteamWorkshopCollections.SOURCE_WEBAPI));
		assertNull(SteamWorkshopCollections.parseSteamWorkshopCollectionDetailsResponse("", "3333",
			SteamWorkshopCollections.SOURCE_WEBAPI));
		assertNull(SteamWorkshopCollections.parseSteamWorkshopCollectionDetailsResponse("{broken", "3333",
			SteamWorkshopCollections.SOURCE_WEBAPI));
	}

	@Test public void communityPageParsesCollectionChildrenBlock() throws Exception {
		String html = "<div class=\"workshopItemTitle\">Fixture Collection</div>"
			+ "<div id=\"collectionChildren\">"
			+ "<a href=\"https://steamcommunity.com/sharedfiles/filedetails/?id=90001\"><div class=\"collectionItem\">A</div></a>"
			+ "<a href=\"https://steamcommunity.com/sharedfiles/filedetails/?id=90002&amp;inside=1\"><div class=\"collectionItem\">B</div></a>"
			+ "<a href=\"https://steamcommunity.com/sharedfiles/filedetails/?id=3333\"><div class=\"collectionItem\">self</div></a>"
			+ "</div>";

		Object parsed = SteamWorkshopCollections.parseSteamWorkshopCollectionPage(html, "3333",
			SteamWorkshopCollections.SOURCE_COMMUNITY_PAGE);
		assertNotNull(parsed);
		assertEquals("Fixture Collection", field(parsed, "title"));
		List<?> children = children(parsed);
		assertEquals(2, children.size());
		assertEquals("90001", childField(children.get(0), "publishedFileId"));
		assertEquals("90002", childField(children.get(1), "publishedFileId"));
	}

	@Test public void communityPageFallsBackToCollectionItemSegmentsWithoutChildrenBlock() throws Exception {
		String html = "<div class=\"workshopItemTitle\">Fallback Collection</div>"
			+ "<div class=\"collectionItem\"><a href=\"filedetails/?id=90001\">A</a></div>"
			+ "<div>unrelated</div>"
			+ "<div class=\"collectionItem\"><a href=\"filedetails/?id=90003\">B</a></div>";

		Object parsed = SteamWorkshopCollections.parseSteamWorkshopCollectionPage(html, "3333",
			SteamWorkshopCollections.SOURCE_COMMUNITY_PAGE);
		assertNotNull(parsed);
		List<?> children = children(parsed);
		assertEquals(2, children.size());
		assertEquals("90001", childField(children.get(0), "publishedFileId"));
		assertEquals("90003", childField(children.get(1), "publishedFileId"));
	}

	@Test public void communityPageWithoutChildItemsReturnsNull() throws Exception {
		String html = "<div id=\"collectionChildren\">nothing here</div><div>plain page</div>";
		assertNull(SteamWorkshopCollections.parseSteamWorkshopCollectionPage(html, "3333",
			SteamWorkshopCollections.SOURCE_COMMUNITY_PAGE));
		assertNull(SteamWorkshopCollections.parseSteamWorkshopCollectionPage("", "3333",
			SteamWorkshopCollections.SOURCE_COMMUNITY_PAGE));
	}

	@Test public void loadCollectionRejectsInvalidCollectionId() throws Exception {
		SteamWorkshopCollections collections = new SteamWorkshopCollections(RuntimeEnvironment.getApplication(), null);
		try {
			collections.loadCollection("not-a-number");
			fail("Expected invalid collection id to be rejected.");
		} catch (Exception exception) {
			assertTrue(exception.getMessage().contains("ID"));
		}
	}

	private static List<?> children(Object parsed) throws Exception {
		Object children = field(parsed, "children");
		assertTrue(children instanceof List);
		return (List<?>) children;
	}

	private static Object childField(Object child, String name) throws Exception {
		return field(child, name);
	}

	private static int childFieldInt(Object child, String name) throws Exception {
		return (Integer) field(child, name);
	}

	private static Object field(Object owner, String name) throws Exception {
		java.lang.reflect.Field field = owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(owner);
	}
}
