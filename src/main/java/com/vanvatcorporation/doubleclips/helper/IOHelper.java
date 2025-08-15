package com.vanvatcorporation.doubleclips.helper;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.RequiresApi;

import com.vanvatcorporation.doubleclips.manager.LoggingManager;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;


public class IOHelper {
    public static final String WORKING_DIRECTORY = "/storage/emulated/0/DevUtilities/";
    public static final String USER_DIRECTORY = "/storage/emulated/0/";

    public static String CombinePath(String... paths)
    {
        StringBuilder finalPath = new StringBuilder("/");
        for (String path : paths)
        {
            finalPath.append("/").append(path);
        }
        for (int i = 0; i < finalPath.length(); i++) {
            if (finalPath.charAt(i) == '\\')
            {
                finalPath.replace(i, i, "/");
            }
        }
        for (int i = 0; i < finalPath.length(); i++) {
            if (finalPath.charAt(i) == '/')
            {
                int deleteCount = 0;
                int currentIndex = i + 1;
                if(i + 1 < finalPath.length())
                {
                    while (finalPath.charAt(currentIndex) == '/' && currentIndex < finalPath.length())
                    {
                        deleteCount++;
                        currentIndex++;
                    }
                }
                finalPath = deleteCount == 0 ? finalPath : finalPath.delete(i, i+deleteCount);
            }
        }
        return finalPath.toString();
    }
    public static String CombineWorkingDirectoryPath(String... paths)
    {
        return CombinePath(WORKING_DIRECTORY, CombinePath(paths));
    }

    public static String getNextIndexPathInFolder(Context context, String folderPath, boolean createEmptyFile)
    {
        return getNextIndexPathInFolder(context, folderPath, "", createEmptyFile);
    }
    public static String getNextIndexPathInFolder(Context context, String folderPath, String extension, boolean createEmptyFile)
    {
        return getNextIndexPathInFolder(context, folderPath, "", extension, createEmptyFile);
    }
    public static String getNextIndexPathInFolder(Context context, String folderPath, String prefix, String extension, boolean createEmptyFile)
    {
        int nonexistentFileIndex = 0;
        while(IOHelper.isFileExist(IOHelper.CombinePath(folderPath, prefix + nonexistentFileIndex) + extension))
            nonexistentFileIndex++;
        String newFilePath = IOHelper.CombinePath(folderPath, prefix + nonexistentFileIndex) + extension;
        if(createEmptyFile)
            IOHelper.createEmptyFileIfNotExist(context, newFilePath);
        return newFilePath;
    }


    public static int getFileSize(Context context, String filePath)
    {
        File workingFile = new File(filePath);
        if (!workingFile.exists())
        {
            workingFile.getParentFile().mkdirs();
            return 0;
        }
        int size = 0;
        try {
            if(workingFile.isDirectory())
            {
                for (File file : workingFile.listFiles()) {
                    size += getFileSize(context, file.getPath());
                }
            }
            else {
                size = (int) workingFile.length();
            }
            return size;
        }
        catch (Exception e)
        {
            LoggingManager.LogExceptionToNoteOverlay(context, e);
        }

        return 0;
    }

    public static String getPersistentDataPath(Context context) {
        return context.getExternalFilesDir(null).getAbsolutePath();
    }

    public static String readFromFile(Context context, String filePath)
    {
        try
        {
            File workingFile = new File(filePath);
            if (!workingFile.exists())
            {
                workingFile.getParentFile().mkdirs();
                return "";
            }
            BufferedReader reader = new BufferedReader(new FileReader(filePath));
            StringBuilder stringBuilder = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null)
            {
                stringBuilder.append(line);
                stringBuilder.append(System.lineSeparator());
            }

            reader.close();
            return stringBuilder.toString();

        }
        catch (IOException e)
        {
            // TODO: handle exception
            LoggingManager.LogExceptionToNoteOverlay(context, e);
            return "";
        }
    }
    public static String readFromFile(Context context, ContentResolver resolver, Uri uri)
    {
        try
        {
//            File workingFile = new File(uri.toString());
//            if (!workingFile.exists())
//            {
//                workingFile.getParentFile().mkdirs();
//                return "";
//            }
            InputStream inputStream = resolver.openInputStream(uri);

            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            StringBuilder stringBuilder = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null)
            {
                stringBuilder.append(line);
                stringBuilder.append(System.lineSeparator());
            }

            reader.close();
            return stringBuilder.toString();

        }
        catch (FileNotFoundException e)
        {
            // TODO: handle exception
            LoggingManager.LogExceptionToNoteOverlay(context, e);
            return "";
        }
        catch (IOException e)
        {
            // TODO: handle exception
            LoggingManager.LogExceptionToNoteOverlay(context, e);
            return "";
        }
    }
//    public static byte[] readFromFileAsRaw(Context context, String filePath)
//    {
//        File workingFile = new File(filePath);
//        if (!workingFile.exists())
//        {
//            workingFile.getParentFile().mkdirs();
//            return new byte[0];
//        }
//        try {
//            ByteArrayOutputStream output = new ByteArrayOutputStream();
//            if(workingFile.isDirectory())
//            {
//                for (File file : workingFile.listFiles()) {
//                    int size = (int) file.length();
//                    byte[] bytes = new byte[size];
//                    byte[] tmpBuff = new byte[size];
//                    FileInputStream fis= new FileInputStream(file);
//
//
//                    int read = fis.read(bytes, 0, size);
//                    if (read < size) {
//                        int remain = size - read;
//                        while (remain > 0) {
//                            read = fis.read(tmpBuff, 0, remain);
//                            System.arraycopy(tmpBuff, 0, bytes, size - remain, read);
//                            remain -= read;
//                        }
//                    }
//
//                    fis.close();
//                    output.write(bytes);
//                }
//                return output.toByteArray();
//            }
//            else {
//                int size = (int) workingFile.length();
//                byte[] bytes = new byte[size];
//                byte[] tmpBuff = new byte[size];
//                FileInputStream fis= new FileInputStream(workingFile);
//
//
//                int read = fis.read(bytes, 0, size);
//                if (read < size) {
//                    int remain = size - read;
//                    while (remain > 0) {
//                        read = fis.read(tmpBuff, 0, remain);
//                        System.arraycopy(tmpBuff, 0, bytes, size - remain, read);
//                        remain -= read;
//                    }
//                }
//
//                fis.close();
//                return bytes;
//            }
//        }
//        catch (Exception e)
//        {
//            LoggingManager.LogExceptionToNoteOverlay(context, e);
//        }
//
//        return new byte[0];
//    }
//
//
//    public static byte[] readFromFileAsRaw(Context context, ContentResolver resolver, Uri uri)
//    {
//        try {
//                InputStream fis = resolver.openInputStream(uri);
//
//                AssetFileDescriptor fileDescriptor = resolver.openAssetFileDescriptor(uri , "r");
//                int size = (int) fileDescriptor.getLength();
//                fileDescriptor.close();
//                byte[] bytes = new byte[size];
//                byte[] tmpBuff = new byte[size];
//
//
//                assert fis != null;
//                int read = fis.read(bytes, 0, size);
//                if (read < size) {
//                    int remain = size - read;
//                    while (remain > 0) {
//                        read = fis.read(tmpBuff, 0, remain);
//                        System.arraycopy(tmpBuff, 0, bytes, size - remain, read);
//                        remain -= read;
//                    }
//                }
//
//                fis.close();
//                return bytes;
//        }
//        catch (Exception e)
//        {
//            LoggingManager.LogExceptionToNoteOverlay(context, e);
//        }
//
//        return new byte[0];
//    }


    public static byte[] readFromFileAsRaw(Context context, String filePath) {
        File file = new File(filePath);

        if (!file.exists()) {
            LoggingManager.LogToNoteOverlay(context, "File doesn't exist: " + filePath);
            return new byte[0];
        }

        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file));
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[65536]; // 64KB buffer
            int bytesRead;
            while ((bytesRead = bis.read(buffer)) != -1) {
                bos.write(buffer, 0, bytesRead);
            }

            return bos.toByteArray();
        } catch (IOException e) {
            LoggingManager.LogExceptionToNoteOverlay(context, e);
        }

        return new byte[0];
    }
    public static byte[] readFromFileAsRaw(Context context, ContentResolver resolver, Uri uri) {
        try (InputStream inputStream = resolver.openInputStream(uri);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {

            if (inputStream == null) {
                LoggingManager.LogToNoteOverlay(context, "Unable to open URI: " + uri.toString());
                return new byte[0];
            }

            byte[] buffer = new byte[65536]; // 64KB buffer
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                bos.write(buffer, 0, bytesRead);
            }

            return bos.toByteArray();
        } catch (Exception e) {
            LoggingManager.LogExceptionToNoteOverlay(context, e);
        }

        return new byte[0];
    }




    public static void writeToFile(Context context, String filePath, String content)
    {
        try
        {
            createEmptyFile(context, filePath);
            BufferedWriter writer = new BufferedWriter(new FileWriter(filePath));
            writer.write(content);

            writer.close();
        }

        catch (IOException e)
        {
            // TODO: handle exception
            LoggingManager.LogExceptionToNoteOverlay(context, e);
        }
    }
    public static void writeToFile(Context context, ContentResolver resolver, Uri uri, String content)
    {
        try
        {
            createEmptyFile(resolver, uri);
            OutputStream outputStream = resolver.openOutputStream(uri);
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream));
            writer.write(content);

            writer.close();
        }

        catch (IOException e)
        {
            // TODO: handle exception
            LoggingManager.LogExceptionToNoteOverlay(context, e);
        }
    }

    public static void writeToFileAsRaw(Context context, String filePath, byte[] content)
    {
        try {
            createEmptyFile(context, filePath);
            File workingFile = new File(filePath);
            if (!workingFile.exists())
            {
                workingFile.getParentFile().mkdirs();
            }

            FileOutputStream fos = new FileOutputStream(workingFile);

            fos.write(content);
            fos.close();
        }
        catch (Exception e)
        {
            LoggingManager.LogExceptionToNoteOverlay(context, e);
        }
    }
    public static void writeToFileAsRaw(Context context, ContentResolver resolver, Uri uri, byte[] content)
    {
        try {
            createEmptyFile(resolver, uri);
//            File workingFile = new File(filePath);
//            if (!workingFile.exists())
//            {
//                workingFile.getParentFile().mkdirs();
//            }

            OutputStream fos = resolver.openOutputStream(uri);

            assert fos != null;
            fos.write(content);
            fos.close();
        }
        catch (Exception e)
        {
            LoggingManager.LogExceptionToNoteOverlay(context, e);
        }
    }
    public static void appendToFile(Context context, String filePath, String content) {
        try
        {
            File workingFile = new File(filePath);
            if (!workingFile.exists())
            {
                workingFile.getParentFile().mkdirs();
            }
//            FileWriter fileWriter = new FileWriter(workingFile);
//            fileWriter.append("\n").append(content);

            String reader = readFromFile(context, filePath);
            BufferedWriter writer = new BufferedWriter(new FileWriter(filePath));
            writer.write(reader + "\n" + content);

            writer.close();
        }
        catch (IOException e)
        {
            // TODO: handle exception
            LoggingManager.LogExceptionToNoteOverlay(context, e);
            //LoggingManager.LogExceptionToNoteOverlay(e);
            //throw e;
        }
    }
    public static void appendToFile(Context context, ContentResolver resolver, Uri uri, String content) {
        try
        {
//            File workingFile = new File(filePath);
//            if (!workingFile.exists())
//            {
//                workingFile.getParentFile().mkdirs();
//            }
//            FileWriter fileWriter = new FileWriter(workingFile);
//            fileWriter.append("\n").append(content);

            String reader = readFromFile(context, resolver, uri);
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(resolver.openOutputStream(uri)));
            writer.write(reader + "\n" + content);

            writer.close();
        }
        catch (IOException e)
        {
            // TODO: handle exception
            LoggingManager.LogExceptionToNoteOverlay(context, e);
            //LoggingManager.LogExceptionToNoteOverlay(e);
            //throw e;
        }
    }
    public static void appendToFileAsRaw(Context context, String filePath, byte[] content)
    {
        try {

            File workingFile = new File(filePath);
            if (!workingFile.exists())
            {
                workingFile.getParentFile().mkdirs();
            }
            byte[] file = readFromFileAsRaw(context, filePath);
//            byte[] combiningFile = new byte[file.length + content.length];
//            System.arraycopy(file, 0, combiningFile, 0, file.length);
//            System.arraycopy(content, 0, combiningFile, file.length, file.length + content.length);
//            writeToFileAsRaw(filePath, combiningFile);


//            FileOutputStream fos = new FileOutputStream(workingFile);
//
//            fos.write(file, 0, file.length);
//            fos.write(content, file.length, file.length + content.length);
//            fos.close();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            output.write(file);
            output.write(content);
            writeToFileAsRaw(context, filePath, output.toByteArray());
        }
        catch (Exception e)
        {
            LoggingManager.LogExceptionToNoteOverlay(context, e);
        }
    }
    public static void appendToFileAsRaw(Context context, ContentResolver resolver, Uri uri, byte[] content)
    {
        try {

//            File workingFile = new File(filePath);
//            if (!workingFile.exists())
//            {
//                workingFile.getParentFile().mkdirs();
//            }
            byte[] file = readFromFileAsRaw(context, resolver, uri);
//            byte[] combiningFile = new byte[file.length + content.length];
//            System.arraycopy(file, 0, combiningFile, 0, file.length);
//            System.arraycopy(content, 0, combiningFile, file.length, file.length + content.length);
//            writeToFileAsRaw(filePath, combiningFile);


//            FileOutputStream fos = new FileOutputStream(workingFile);
//
//            fos.write(file, 0, file.length);
//            fos.write(content, file.length, file.length + content.length);
//            fos.close();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            output.write(file);
            output.write(content);
            writeToFileAsRaw(context, resolver, uri, output.toByteArray());
        }
        catch (Exception e)
        {
            LoggingManager.LogExceptionToNoteOverlay(context, e);
        }
    }


    public static void appendToFileTrunc(Context context, String filePath, String content, int truncByte) {
        try {
            File workingFile = new File(filePath);
            if (!workingFile.exists()) {
                workingFile.getParentFile().mkdirs();
            }

            String existing = readFromFile(context, filePath);
            if (existing.length() > truncByte) {
                existing = existing.substring(existing.length() - truncByte);
            }

            BufferedWriter writer = new BufferedWriter(new FileWriter(filePath));
            writer.write(existing + "\n" + content);
            writer.close();
        } catch (IOException e) {
            LoggingManager.LogExceptionToNoteOverlay(context, e);
        }
    }
    public static void appendToFileTrunc(Context context, ContentResolver resolver, Uri uri, String content, int truncByte) {
        try {
            String existing = readFromFile(context, resolver, uri);
            if (existing.length() > truncByte) {
                existing = existing.substring(existing.length() - truncByte);
            }

            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(resolver.openOutputStream(uri)));
            writer.write(existing + "\n" + content);
            writer.close();
        } catch (IOException e) {
            LoggingManager.LogExceptionToNoteOverlay(context, e);
        }
    }
    public static void appendToFileTruncAsRaw(Context context, String filePath, byte[] content, int truncByte) {
        try {
            File workingFile = new File(filePath);
            if (!workingFile.exists()) {
                workingFile.getParentFile().mkdirs();
            }

            byte[] existing = readFromFileAsRaw(context, filePath);
            if (existing.length > truncByte) {
                existing = Arrays.copyOfRange(existing, existing.length - truncByte, existing.length);
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            output.write(existing);
            output.write(content);
            writeToFileAsRaw(context, filePath, output.toByteArray());
        } catch (Exception e) {
            LoggingManager.LogExceptionToNoteOverlay(context, e);
        }
    }
    public static void appendToFileTruncAsRaw(Context context, ContentResolver resolver, Uri uri, byte[] content, int truncByte) {
        try {
            byte[] existing = readFromFileAsRaw(context, resolver, uri);
            if (existing.length > truncByte) {
                existing = Arrays.copyOfRange(existing, existing.length - truncByte, existing.length);
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            output.write(existing);
            output.write(content);
            writeToFileAsRaw(context, resolver, uri, output.toByteArray());
        } catch (Exception e) {
            LoggingManager.LogExceptionToNoteOverlay(context, e);
        }
    }

    public static void createEmptyFile(Context context, String filePath)
    {
        try
        {
            if (!((new File(filePath)).exists()))
            {
                (new File(filePath)).getParentFile().mkdirs();
            }
            (new File(filePath)).createNewFile();
            BufferedWriter writer = new BufferedWriter(new FileWriter(filePath));
            writer.write("");

            writer.close();
        }
        catch (IOException e)
        {
            // TODO: handle exception
            LoggingManager.LogExceptionToNoteOverlay(context, e);
        }
    }
    public static void createEmptyFile(ContentResolver resolver, Uri uri)
    {
        //TODO: Yes this code does nothing! Just using the old normal String path for writing.
//        try
//        {
//            if (!((new File(filePath)).exists()))
//            {
//                (new File(filePath)).getParentFile().mkdirs();
//            }
//            (new File(filePath)).createNewFile();
//            BufferedWriter writer = new BufferedWriter(new FileWriter(filePath));
//            writer.write("");
//
//            writer.close();
//        }
//        catch (IOException e)
//        {
//            // TODO: handle exception
//            if (instance != null)
//                LoggingManager.LogExceptionToNoteOverlay(instance, e);
//        }
    }

    public static void createEmptyDirectories(String filePath)
    {
        if (!((new File(filePath)).exists()))
        {
            (new File(filePath)).mkdirs();
        }
    }
    public static void createEmptyFileIfNotExist(Context context, String filePath)
    {
        createEmptyFileIfNotExist(context, filePath, "");
    }
    public static void createEmptyFileIfNotExist(Context context, String filePath, String additionalText)
    {
        if (!isFileExist(filePath))
        {
            writeToFile(context, filePath, additionalText);
        }
    }
    public static boolean isFileExist(String filePath)
    {
        return ((new File(filePath)).exists());
    }

    public static boolean deleteFile(String filePath)
    {
        File file = new File(filePath);
        return file.delete();
    }
    public static boolean deleteDir(String filePath) {
        File file = new File(filePath);
        return deleteDir(file);
    }
    public static boolean deleteDir(File dir) {
        if (dir.isDirectory()) {
            String[] children = dir.list();
            for (int i=0; i<children.length; i++) {
                boolean success = deleteDir(new File(dir, children[i]));
                if (!success) {
                    return false;
                }
            }
        }

        // The directory is now empty so delete it
        return dir.delete();
    }
    // Delete all files in dir but keep the dir
    public static boolean deleteFilesInDir(String filePath) {
        File file = new File(filePath);
        return deleteFilesInDir(file);
    }
    public static boolean deleteFilesInDir(File dir) {
        if (dir.isDirectory()) {
            String[] children = dir.list();
            for (int i=0; i<children.length; i++) {
                boolean success = deleteFilesInDir(new File(dir, children[i]));
                if (!success) {
                    return false;
                }
            }
        }
        return true;
    }



    public enum FileListing
    {
        DIRECTORIES, FILES, BOTH
    }
    public static File[] listFiles(Context context, String directoryPath, FileListing options) {
        // Create a File object
        File directory = new File(directoryPath);

        // Get all files and directories within the specified directory
        File[] files = directory.listFiles();
        ArrayList<File> fileArrayList = new ArrayList<>();



        // Check if the directory is valid and not empty
        if (files != null) {
            switch (options)
            {
                case FILES:
                {
                    for (File file : files) {
                        // Check if the file is a directory
                        if (!file.isDirectory()) {
                            fileArrayList.add(file);
                        }
                    }
                    break;
                }
                case DIRECTORIES:
                {
                    for (File file : files) {
                        // Check if the file is a directory
                        if (file.isDirectory()) {
                            fileArrayList.add(file);
                        }
                    }
                    break;
                }
                case BOTH:
                {
                    fileArrayList.addAll(Arrays.asList(files));
                    break;
                }
            }

        } else {
            LoggingManager.LogToNoteOverlay(context, "The specified directory is invalid or empty.");
        }
        return fileArrayList.toArray(new File[0]);
    }
}
