package com.vanvatcorporation.doubleclips.helper;

import android.content.Context;
import android.os.Build;

import androidx.annotation.RequiresApi;

import com.google.gson.Gson;
import com.vanvatcorporation.doubleclips.manager.LoggingManager;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class WebHelper {
    public static String WebTitleFetcher(Context context, String url) {
        try {

            Document doc = Jsoup.connect(url).get();
            return doc.title();
        } catch (Exception e) {

            LoggingManager.LogExceptionToNoteOverlay(context, e);
            return "";
        }
    }
    public static void saveWebpage(Context context, String url, savePagesSettings settings, String customName, ArrayList<String> visitedWebpages)
    {
        saveWebpage(context, url, IOHelper.CombineWorkingDirectoryPath("SavedWebpages/", (customName.isEmpty() ? WebHelper.WebTitleFetcher(context, url) : customName)), settings, visitedWebpages);
    }

    public static void saveWebpage(Context context, String url, String saveDir, savePagesSettings settings, ArrayList<String> visitedWebpages) {

        try {
            visitedWebpages.add(url);

            SavedWebpageAttribute attribute = new SavedWebpageAttribute();

            attribute.originalWebUrl = url;
            attribute.savedWhen = new Date().getTime();


            attribute.domain = getDomainFromWeb(url);




            saveDir += "/";
            // Create save directory
            IOHelper.createEmptyDirectories(saveDir);

            IOHelper.writeToFile(context, IOHelper.CombinePath( saveDir, "savedAttribute.txt"), new Gson().toJson(attribute));

            // Fetch HTML content
            Document doc = Jsoup.connect(url).get();

            // Download images
            for (Element img : doc.select("img[src]")) {
                String imgUrl = img.attr("abs:src");
                String fileName = saveDir + imgUrl.substring(imgUrl.indexOf("/", 10) + 1);
                IOHelper.createEmptyFile(context, fileName);
                downloadFile(context, imgUrl, fileName);
                img.attr("src", fileName); // Update the src to the local path
            }

            // Download audio files
            for (Element audio : doc.select("audio[src], audio source[src]")) {
                String audioUrl = audio.attr("abs:src");
                if (!audioUrl.isEmpty()) {
                    String fileName = saveDir + audioUrl.substring(audioUrl.lastIndexOf("/") + 1);
                    IOHelper.createEmptyFile(context, fileName);
                    downloadFile(context, audioUrl, fileName);
                    audio.attr("src", fileName); // Update the src to local path
                }
            }

            // Download video files
            for (Element video : doc.select("video[src], video source[src]")) {
                String videoUrl = video.attr("abs:src");
                if (!videoUrl.isEmpty()) {
                    String fileName = saveDir + videoUrl.substring(videoUrl.lastIndexOf("/") + 1);
                    IOHelper.createEmptyFile(context, fileName);
                    downloadFile(context, videoUrl, fileName);
                    video.attr("src", fileName); // Update the src to local path
                }
            }

            // Download CSS files
            for (Element css : doc.select("link[rel=stylesheet]")) {
                String cssUrl = css.attr("abs:href");
                if (!cssUrl.isEmpty()) {
                    String fileName = saveDir + cssUrl.substring(cssUrl.lastIndexOf("/") + 1);
                    IOHelper.createEmptyFile(context, fileName);
                    downloadFile(context, cssUrl, fileName);
                    css.attr("href", fileName); // Update href to local path
                }
            }

            // Download JavaScript files
            for (Element script : doc.select("script[src]")) {
                String scriptUrl = script.attr("abs:src");
                if (!scriptUrl.isEmpty()) {
                    String fileName = saveDir + scriptUrl.substring(scriptUrl.lastIndexOf("/") + 1);
                    IOHelper.createEmptyFile(context, fileName);
                    downloadFile(context, scriptUrl, fileName);
                    script.attr("src", fileName); // Update src to local path
                }
            }

            if(settings.isRecursive)
            {
                // Extract links from various tags
                for (Element tag : doc.select("a[href]")) { // Tags with href attribute
                    boolean isContinue = false;
                    String href = tag.attr("abs:href");
                    String rawHref = tag.attr("href");
                    for (String str : visitedWebpages) {
                        if(href.equals(str)) {
                            isContinue = true;
                            break;
                        }
                    }

                    if(settings.isInternalDomainDownload)
                    {
                        String domain = getDomainFromWeb(href);
                        if(!domain.equals(attribute.domain))
                        {
                            isContinue = true;
                        }
                    }

                    if(isContinue) continue;

                    tag.attr("href", rawHref + "/index.html"); // Update src to local path

                    saveWebpage(context, href, settings, settings.useATagNameForRecursiveWebName ? rawHref.replace(".", "") : "", visitedWebpages);
                }
            }


            // Save the updated HTML
            FileWriter writer = new FileWriter(saveDir + "index.html");
            writer.write(doc.html());
            writer.close();

            LoggingManager.LogToNotification(context, "Download completed", url);
        } catch (Exception e) {
            LoggingManager.LogExceptionToNoteOverlay(context, e);
        }
    }

    public static void downloadFile(Context context, String fileURL, String savePath) {
        try (InputStream in = new URL(fileURL).openStream();
             FileOutputStream out = new FileOutputStream(savePath)) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        } catch (Exception e) {
            LoggingManager.LogExceptionToNoteOverlay(context, e);
        }
    }






    public static String getDomainFromWeb(String url)
    {
        String domain = url.replace("http://", "").replace("https://", "");
        return domain.substring(0, (!domain.contains("/") ? domain.length() : domain.indexOf("/")));
    }


    public static class savePagesSettings
    {
        public savePagesSettings(boolean isRecursive, boolean isInternalDomainDownload, boolean useATagNameForRecursiveWebName)
        {
            this.isRecursive = isRecursive;
            this.isInternalDomainDownload = isInternalDomainDownload;
            this.useATagNameForRecursiveWebName = useATagNameForRecursiveWebName;
        }

        public boolean isRecursive;
        public boolean isInternalDomainDownload;
        public boolean useATagNameForRecursiveWebName;
    }


    public static class SavedWebpageAttribute
    {
        public String domain;
        public String originalWebUrl;
        public long savedWhen;
    }
}