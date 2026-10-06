package com.vanvatcorporation.doubleclips.helper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.vanvatcorporation.doubleclips.activities.EditingActivity;

public class GsonHelper {
    public static <T> Gson createStrictGson(String[] requiredFields, Class<T> clazz)
    {
        return new GsonBuilder()
                .registerTypeAdapter(clazz, (JsonDeserializer<T>) (json, typeOfT, context) -> {
                    JsonObject obj = json.getAsJsonObject();
                    // If the JSON doesn't have a specific Clip field, throw error
                    for (String field : requiredFields) {
                        if (!obj.has(field)) {
                            throw new JsonParseException("Not a valid object");
                        }
                    }
                    return com.vanvatcorporation.doubleclips.ProjectGson.forLoad().fromJson(obj, clazz);
                })
                .create();
    }
    public static Gson createExposeOnlyGson()
    {
        // Same expose-only layout as before, plus keeping the keys this build doesn't know (see ProjectGson).
        return com.vanvatcorporation.doubleclips.ProjectGson.forSave();
    }
}
