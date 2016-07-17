package info.jdavid.github.notifier

import groovy.transform.CompileStatic
import info.jdavid.ok.json.Builder
import info.jdavid.ok.json.Parser
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.Okio


@CompileStatic
public class ReleaseNotifier {

  public static void main(final String[] args) {
    final File root = new File('./repos')
    if (!root.exists()) throw new RuntimeException("List of repos is missing.")
    final OkHttpClient client = new OkHttpClient.Builder().build()
    root.listFiles().collect { final File f ->
      repo(client, f)
    }

  }

  private static Map<String, ?> repo(final OkHttpClient client, final File f) {
    final Map<String, ?> cur = Parser.parse(Okio.buffer(Okio.source(f))) as Map<String, ?>
    final String owner = cur.owner as String
    final String repo = cur.repo as String
    final HttpUrl url = new HttpUrl.Builder().
      scheme('https').
      host('api.github.com').
      addPathSegment('repos').
      addPathSegment(owner).
      addPathSegment(repo).
      addPathSegment('git').
      addPathSegment('refs').
      addPathSegment('tags').
      build()
    final Request request = new Request.Builder().
      url(url).
      build()
    final Response response = client.newCall(request).execute()
    if (response.isSuccessful()) {
      final List<?> result = Parser.parse(response.body().source()) as List<?>
      final List<String> allTags = result.collect {
        final Map<String, ?> map = it as Map<String, ?>
        final String ref = map.ref as String
        assert ref.indexOf('refs/tags/') == 0
        return ref.substring(10)
      }
      final List<String> curTags = cur.tags as List<String>
      final List<String> newTags = allTags.collect().with { removeAll(curTags); it }
      if (newTags.isEmpty()) return null
      if (send(client, owner, repo, newTags)) {
        curTags.clear()
        curTags.addAll(allTags)
        Builder.build(Okio.buffer(Okio.sink(f)), cur)
      }
    }
    else {
      println(response.body().string())
    }
    return null
  }

  private static boolean send(final OkHttpClient client,
                              final String owner, final String repo, final List<String> newTags) {
    final Properties props = new File('./local.properties').withReader {
      final Properties props = new Properties()
      props.load(it)
      return props
    }
    final String apiKey = props.getProperty('mailchimpApiKey')
    final String listId = props.getProperty('mailchimpListId')
    final String email = props.getProperty('mailchimpEmail')
    final String server = apiKey.substring(apiKey.lastIndexOf('-') + 1)
    final String campaign = createCampaign(client, apiKey, server, listId, email, owner, repo)
    if (campaign) {
      if (setCampaignContent(client, apiKey, server, campaign, owner, repo, newTags)) {
        if (sendCampaign(client, apiKey, server, campaign)) {
          return true
        }
      }
      deleteCampaign(client, apiKey, server, campaign)
    }
    return false
  }

  private static String createCampaign(final OkHttpClient client,
                                       final String apiKey, final String server,
                                       final String listId, final String email,
                                       final String owner, final String repo) {
    final HttpUrl url = new HttpUrl.Builder().
      scheme('https').
      host("${server}.api.mailchimp.com").
      addPathSegment('3.0').
      addPathSegment('campaigns').
      build()
    final Map<String, ?> json = [
      type: 'plaintext',
      recipients: [
        list_id: listId
      ],
      settings: [
        subject_line: "New release for ${owner}/${repo}",
        title: "${owner}/${repo}",
        from_name: email,
        reply_to: email
      ]
    ]
    final Request request = new Request.Builder().
      url(url).
      header('Authorization', "apikey ${apiKey}").
      post(RequestBody.create(MediaType.parse('application/json'), Builder.build(json))).
      build()
    final Response response = client.newCall(request).execute()
    if (response.isSuccessful()) {
      final Map<String, ?> result = Parser.parse(response.body().source()) as Map<String, ?>
      return result.id as String
    }
    else {
      println url
      println response.body().string()
    }
  }

  private static boolean setCampaignContent(final OkHttpClient client,
                                            final String apiKey, final String server,
                                            final String campaign,
                                            final String owner, final String repo,
                                            final List<String> newTags) {
    final HttpUrl url = new HttpUrl.Builder().
      scheme('https').
      host("${server}.api.mailchimp.com").
      addPathSegment('3.0').
      addPathSegment('campaigns').
      addPathSegment(campaign).
      addPathSegment('content').
      build()
    final Map<String, ?> json = [
      plain_text: $/
  Repository: ${owner}/\${repo}
  New ${newTags.size() == 1 ? 'Tag' : 'Tags'}: ${newTags.join(', ')}
/$ as String,
    ]
    final Request request = new Request.Builder().
      url(url).
      header('Authorization', "apikey ${apiKey}").
      put(RequestBody.create(MediaType.parse('application/json'), Builder.build(json))).
      build()
    final Response response = client.newCall(request).execute()
    if (response.isSuccessful()) {
      response.body().close()
      return true
    }
    else {
      println url
      println response.body().string()
    }
  }

  private static boolean sendCampaign(final OkHttpClient client,
                                      final String apiKey, final String server,
                                      final String campaign) {
    final HttpUrl url = new HttpUrl.Builder().
      scheme('https').
      host("${server}.api.mailchimp.com").
      addPathSegment('3.0').
      addPathSegment('campaigns').
      addPathSegment(campaign).
      addPathSegment('actions').
      addPathSegment('send').
      build()
    final Request request = new Request.Builder().
      url(url).
      header('Authorization', "apikey ${apiKey}").
      post(RequestBody.create(MediaType.parse('application/json'), new byte[0])).
      build()
    final Response response = client.newCall(request).execute()
    if (response.isSuccessful()) {
      response.body().close()
      return true
    }
    else {
      println url
      println response.body().string()
    }
  }

  private static void deleteCampaign(final OkHttpClient client, final String apiKey, final String server,
                                     final String campaign) {
    final HttpUrl url = new HttpUrl.Builder().
      scheme('https').
      host("${server}.api.mailchimp.com").
      addPathSegment('3.0').
      addPathSegment('campaigns').
      addPathSegment(campaign).
      build()
    final Request request = new Request.Builder().
      url(url).
      header('Authorization', "apikey ${apiKey}").
      delete().
      build()
    final Response response = client.newCall(request).execute()
    if (response.isSuccessful()) {
      response.body().close()
    }
    else {
      println url
      println response.body().string()
      Thread.sleep(5000)
      client.newCall(request).execute().body().close()
    }
  }

}
