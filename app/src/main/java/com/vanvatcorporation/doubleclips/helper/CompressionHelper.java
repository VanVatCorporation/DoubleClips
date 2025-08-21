package com.vanvatcorporation.doubleclips.helper;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import com.vanvatcorporation.doubleclips.manager.LoggingManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class CompressionHelper {
    public static void zipFolder(Context context, String srcFolder, ContentResolver resolver, Uri destUri) {
        try {
            OutputStream fos = resolver.openOutputStream(destUri);
            ZipOutputStream zip = new ZipOutputStream(fos);

            addFolderToZip(context, "", srcFolder, zip);
            zip.flush();
            zip.close();
        }
        catch (Exception e)
        {
            LoggingManager.LogExceptionToNoteOverlay(context, e);
        }
    }
    public static void zipFolder(Context context, String srcFolder, String destZipFile) {
        try {
            ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(destZipFile));
            addFolderToZip(context, "", srcFolder, zip);
            zip.flush();
            zip.close();
        }
        catch (Exception e)
        {
            LoggingManager.LogExceptionToNoteOverlay(context, e);
        }
    }

//    public static void unzipFolder(Context context, String zipFilePath, ContentResolver resolver, Uri destDir) {
//        try {
//            File dir = new File(resolver.);
//            if (!dir.exists()) dir.mkdirs();
//
//            ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFilePath));
//            ZipEntry entry;
//            while ((entry = zis.getNextEntry()) != null) {
//                File newFile = new File(destDir, entry.getName());
//                if (entry.isDirectory()) {
//                    newFile.mkdirs();
//                } else {
//                    new File(Objects.requireNonNull(newFile.getParent())).mkdirs();
//                    try (FileOutputStream fos = new FileOutputStream(newFile)) {
//                        byte[] buffer = new byte[1024];
//                        int len;
//                        while ((len = zis.read(buffer)) > 0) {
//                            fos.write(buffer, 0, len);
//                        }
//                    }
//                }
//                zis.closeEntry();
//            }
//        }
//        catch (Exception e)
//        {
//            LoggingManager.LogExceptionToNoteOverlay(context, e);
//        }
//    }

    public static void unzipFolder(Context context, String zipFilePath, String destDir) {
        try {
            File dir = new File(destDir);
            if (!dir.exists()) dir.mkdirs();

            ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFilePath));
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File newFile = new File(destDir, entry.getName());
                if (entry.isDirectory()) {
                    newFile.mkdirs();
                } else {
                    new File(Objects.requireNonNull(newFile.getParent())).mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(newFile)) {
                        byte[] buffer = new byte[1024];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
        catch (Exception e)
        {
            LoggingManager.LogExceptionToNoteOverlay(context, e);
        }
    }
    public static void unzipFolder(Context context, ContentResolver resolver, Uri zipUri, String destDir) {
        try {
            File dir = new File(destDir);
            if (!dir.exists()) dir.mkdirs();

            ZipInputStream zis = new ZipInputStream(resolver.openInputStream(zipUri));
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File newFile = new File(destDir, entry.getName());
                if (entry.isDirectory()) {
                    newFile.mkdirs();
                } else {
                    new File(Objects.requireNonNull(newFile.getParent())).mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(newFile)) {
                        byte[] buffer = new byte[1024];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
        catch (Exception e)
        {
            LoggingManager.LogExceptionToNoteOverlay(context, e);
        }
    }











    private static void addFileToZip(Context context, String path, String srcFile, ZipOutputStream zip) {
        try {
            File folder = new File(srcFile);
            if (folder.isDirectory()) {
                addFolderToZip(context, path, srcFile, zip);
            } else {
                byte[] buf = new byte[1024];
                int len;
                FileInputStream in = new FileInputStream(srcFile);
                zip.putNextEntry(new ZipEntry(path + "/" + folder.getName()));
                while ((len = in.read(buf)) > 0) {
                    zip.write(buf, 0, len);
                }
                in.close();
            }
        }
        catch (Exception e)
        {
            LoggingManager.LogExceptionToNoteOverlay(context, e);
        }
    }

    private static void addFolderToZip(Context context, String path, String srcFolder, ZipOutputStream zip) {

        try {
            File folder = new File(srcFolder);
            for (String fileName : Objects.requireNonNull(folder.list())) {
                if (path.equals("")) {
                    addFileToZip(context, folder.getName(), srcFolder + "/" + fileName, zip);
                } else {
                    addFileToZip(context, path + "/" + folder.getName(), srcFolder + "/"
                            + fileName, zip);
                }
            }
        }
        catch (Exception e)
        {
            LoggingManager.LogExceptionToNoteOverlay(context, e);
        }
    }
}
