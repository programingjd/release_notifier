package info.jdavid.github.notifier;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import info.jdavid.ok.json.Builder;
import info.jdavid.ok.json.Parser;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Okio;


public class ReleaseNotifier {

  public static void main(final String[] args) {
    final File root = new File(getProjectDir(), "repos");
    if (!root.exists()) {
      throw new RuntimeException("List of repos is missing.");
    }
    final OkHttpClient client = new OkHttpClient.Builder().build();
    final File[] files = root.listFiles();
    if (files != null) {
      Arrays.stream(files).
        forEach((final File f) -> repo(client, f));
    }
  }

  private static File getProjectDir() {
    try {
      return new File(
        ReleaseNotifier.class.getProtectionDomain().getCodeSource().getLocation().toURI()
      ).getParentFile().
        getParentFile().
        getParentFile();
    }
    catch (final URISyntaxException e) {
      throw new RuntimeException(e);
    }
  }

  private static Map<String, ?> repo(final OkHttpClient client, final File f) {
    try {
      final Map<String, ?> cur = Parser.parse(Okio.buffer(Okio.source(f)));
      final String owner = (String)cur.get("owner");
      final String repo = (String)cur.get("repo");
      final HttpUrl url = new HttpUrl.Builder().
        scheme("https").
        host("api.github.com").
        addPathSegment("repos").
        addPathSegment(owner).
        addPathSegment(repo).
        addPathSegment("git").
        addPathSegment("refs").
        addPathSegment("tags").
        build();
      final Request request = new Request.Builder().
        url(url).
        build();
      final Response response = client.newCall(request).execute();
      if (response.isSuccessful()) {
        final List<Map<String, ?>> result = Parser.parse(response.body().source());
        final List<String> allTags = result.stream().
          map((final Map<String, ?> map)-> {
            final String ref = (String)map.get("ref");
            assert ref.indexOf("refs/tags/") == 0;
            return ref.substring(10);
          }).
          collect(Collectors.toList());
        //noinspection unchecked
        final List<String> curTags = (List<String>)cur.get("tags");
        final List<String> newTags = allTags.stream().collect(Collectors.toList());
        newTags.removeAll(curTags);
        if (newTags.isEmpty()) {
          return null;
        }
        if (send(client, owner, repo, newTags)) {
          curTags.clear();
          curTags.addAll(allTags);
          Builder.build(Okio.buffer(Okio.sink(f)), cur);
        }
      }
      else {
        System.out.println(response.body().string());
      }
      return null;
    }
    catch (final IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static boolean send(final OkHttpClient client, final String owner, final String repo,
                              final List<String> newTags) {
    try {
      final File local = new File(getProjectDir(), "local.properties");
      final Properties props = new Properties();
      try (final BufferedReader reader = new BufferedReader(new FileReader(local))) {
        props.load(reader);
      }
      final String apiKey = props.getProperty("mailchimpApiKey");
      final String listId = props.getProperty("mailchimpListId");
      final String email = props.getProperty("mailchimpEmail");
      final String server = apiKey.substring(apiKey.lastIndexOf("-") + 1);
      final String campaign = createCampaign(client, apiKey, server, listId, email, owner, repo);
      if (campaign != null && campaign.length() > 0) {
        if (setCampaignContent(client, apiKey, server, campaign, owner, repo, newTags)) {
          if (sendCampaign(client, apiKey, server, campaign)) {
            deleteCampaign(client, apiKey, server, campaign);
            return true;
          }
        }
        deleteCampaign(client, apiKey, server, campaign);
      }
      return false;
    }
    catch (final IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static String createCampaign(final OkHttpClient client, final String apiKey, final String server,
                                       final String listId, final String email, final String owner,
                                       final String repo) throws IOException {
    final HttpUrl url = new HttpUrl.Builder().
      scheme("https").
      host(server + ".api.mailchimp.com").
      addPathSegment("3.0").
      addPathSegment("campaigns").
      build();
    final Map<String, ?> json = map(
      entry("type", "plaintext"),
      entry("recipients", map(
        entry("list_id", listId)
      )),
      entry("settings", map(
        entry("subject_line", "New release for " + owner + "/" + repo),
        entry("title", owner + "/" + repo),
        entry("from_name", email),
        entry("reply_to", email)
      ))
    );
    final Request request = new Request.Builder().
      url(url).
      header("Authorization", "apikey " + apiKey).
      post(RequestBody.create(MediaType.parse("application/json"), Builder.build(json))).
      build();
    final Response response = client.newCall(request).execute();
    if (response.isSuccessful()) {
      final Map<String, ?> result = Parser.parse(response.body().source());
      return (String)result.get("id");
    }
    else {
      System.out.println(url);
      System.out.println(response.body().string());
    }
    return null;
  }

  @SuppressWarnings("Duplicates")
  private static boolean setCampaignContent(final OkHttpClient client, final String apiKey,
                                            final String server, final String campaign, final String owner,
                                            final String repo,
                                            final List<String> newTags) throws IOException {
    final HttpUrl url = new HttpUrl.Builder().
      scheme("https").
      host(server + ".api.mailchimp.com").
      addPathSegment("3.0").
      addPathSegment("campaigns").
      addPathSegment(campaign).
      addPathSegment("content").
      build();
    final Map<String, ?> json = map(
      entry("plain_text",
            "Repository: "+owner+"/"+repo+" (http://github.com/"+owner+"/"+repo+"/release)\n\n" +
            "New " + (newTags.size() == 1 ? "Tag" : "Tags") + ":" + String.join(", ", newTags) +
            "\n\n.\n")
    );
    final Request request = new Request.Builder().
      url(url).
      header("Authorization", "apikey " + apiKey).
      put(RequestBody.create(MediaType.parse("application/json"), Builder.build(json))).
      build();
    final Response response = client.newCall(request).execute();
    if (response.isSuccessful()) {
      response.body().close();
      return true;
    }
    else {
      System.out.println(url);
      System.out.println(response.body().string());
      return false;
    }
  }

  @SuppressWarnings("Duplicates")
  private static boolean sendCampaign(final OkHttpClient client, final String apiKey, final String server,
                                      final String campaign) throws IOException {
    final HttpUrl url = new HttpUrl.Builder().
      scheme("https").
      host(server + ".api.mailchimp.com").
      addPathSegment("3.0").
      addPathSegment("campaigns").
      addPathSegment(campaign).
      addPathSegment("actions").
      addPathSegment("send").
      build();
    final Request request = new Request.Builder().
      url(url).
      header("Authorization", "apikey " + apiKey).
      post(RequestBody.create(MediaType.parse("application/json"), new byte[0])).
      build();
    final Response response = client.newCall(request).execute();
    if (response.isSuccessful()) {
      response.body().close();
      return true;
    }
    else {
      System.out.println(url);
      System.out.println(response.body().string());
      return false;
    }
  }

  private static void deleteCampaign(final OkHttpClient client, final String apiKey, final String server,
                                     final String campaign) throws IOException {
    try { Thread.sleep(5000); } catch (final InterruptedException ignore) {}
    final HttpUrl url = new HttpUrl.Builder().
      scheme("https").
      host(server + ".api.mailchimp.com").
      addPathSegment("3.0").
      addPathSegment("campaigns").
      addPathSegment(campaign).
      build();
    final Request.Builder request = new Request.Builder().
      url(url).
      header("Authorization", "apikey " + apiKey).
      delete();
    final Response response = client.newCall(request.build()).execute();
    if (response.isSuccessful()) {
      response.body().close();
    }
    else {
      response.body().close();
      try { Thread.sleep(15000); } catch (final InterruptedException ignore) {}
      final Response response2 = client.newCall(request.build()).execute();
      if (response2.isSuccessful()) {
        response2.body().close();
      }
      else {
        response2.body().close();
        try { Thread.sleep(30000); } catch (final InterruptedException ignore) {}
        final Response response3 = client.newCall(request.build()).execute();
        if (response3.isSuccessful()) {
          response3.body().close();
        }
        else {
          System.out.println(url);
          System.out.println(response3.body().string());
        }
      }
    }
  }

  private static <K, V> Map.Entry<K, V> entry(final K key, final V value) {
    return new AbstractMap.SimpleEntry<>(key, value);
  }

  @SafeVarargs
  private static <K, V> Map<K, V> map(final Map.Entry<? extends K, ? extends V>... entries) {
    final Map<K, V> map = new LinkedHashMap<>(entries.length);
    for (final Map.Entry<? extends K, ? extends V> entry: entries) {
      map.put(entry.getKey(), entry.getValue());
    }
    return map;
  }

}
