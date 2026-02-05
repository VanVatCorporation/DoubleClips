package com.vanvatcorporation.doubleclips.dynamiclibs.auth;

public class User {
    private String id;
    private String username;
    private String email;
    private String firstName;
    private String lastName;
    private String bio;
    private String avatarUrl;
    private int reputation;

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio; }

    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
    
    public int getReputation() { return reputation; }
    public void setReputation(int reputation) { this.reputation = reputation; }
}
