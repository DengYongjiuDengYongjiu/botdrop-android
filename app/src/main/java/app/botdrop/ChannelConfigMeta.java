package app.botdrop;

/**
 * Static metadata and validation rules for IM channel configuration pages.
 */
public class ChannelConfigMeta {

    public static final String PLATFORM_TELEGRAM = "telegram";
    public static final String PLATFORM_DISCORD = "discord";
    public static final String PLATFORM_FEISHU = "feishu";

    public final String platform;
    public final String title;
    public final String setupBotUrl;
    public final String tokenLabel;
    public final String tokenHint;
    public final String ownerLabel;
    public final String ownerHint;
    public final String setupHelpText;
    public final boolean showOwnerField;

    private ChannelConfigMeta(
        String platform,
        String title,
        String setupBotUrl,
        String tokenLabel,
        String tokenHint,
        String ownerLabel,
        String ownerHint,
        String setupHelpText,
        boolean showOwnerField
    ) {
        this.platform = platform;
        this.title = title;
        this.setupBotUrl = setupBotUrl;
        this.tokenLabel = tokenLabel;
        this.tokenHint = tokenHint;
        this.ownerLabel = ownerLabel;
        this.ownerHint = ownerHint;
        this.setupHelpText = setupHelpText;
        this.showOwnerField = showOwnerField;
    }

    public static ChannelConfigMeta forPlatform(String platform) {
        if (PLATFORM_DISCORD.equals(platform)) {
            return discord();
        }
        if (PLATFORM_FEISHU.equals(platform)) {
            return feishu();
        }
        return telegram();
    }

    public static ChannelConfigMeta telegram() {
        return new ChannelConfigMeta(
            PLATFORM_TELEGRAM,
            "Telegram",
            "https://t.me/BotDropSetupBot",
            "Bot Token (from @BotFather)",
            "123456789:ABCdefGHI...",
            "Your User ID (from @BotDropSetupBot)",
            "123456789",
            "Open the setup bot to create your Telegram bot and get your User ID.",
            true
        );
    }

    public static ChannelConfigMeta discord() {
        return new ChannelConfigMeta(
            PLATFORM_DISCORD,
            "Discord",
            "https://discord.com/developers/applications",
            "Bot Token",
            "bot-token",
            "Owner ID",
            "optional",
            "Create a bot in Discord Developer Portal, get the Bot Token, "
                + "then add the bot to your Guild(Server). It will be available in all guild channels.",
            false
        );
    }

    public static ChannelConfigMeta feishu() {
        return new ChannelConfigMeta(
            PLATFORM_FEISHU,
            "Feishu",
            "https://open.feishu.cn",
            "App ID",
            "app-id",
            "App Secret",
            "app-secret",
            "Create a Feishu app and bot, then fill in App ID and App Secret below. "
                + "See <a href=\"https://docs.openclaw.ai/channels/feishu\">setup guide</a> for details.",
            true
        );
    }

    public boolean isTokenValid(String token) {
        if (token == null || token.trim().isEmpty()) {
            return false;
        }
        if (PLATFORM_TELEGRAM.equals(platform)) {
            return token.matches("^\\d+:[A-Za-z0-9_-]+$");
        }
        return true;
    }

    public boolean isOwnerValid(String ownerId) {
        if (!showOwnerField) {
            return true;
        }
        if (ownerId == null || ownerId.trim().isEmpty()) {
            return false;
        }
        if (PLATFORM_FEISHU.equals(platform)) {
            return true;
        }
        return ownerId.matches("^\\d+$");
    }

    public boolean isDiscordGuildIdValid(String guildId) {
        if (!PLATFORM_DISCORD.equals(platform)) {
            return true;
        }
        if (guildId == null || guildId.trim().isEmpty()) {
            return false;
        }
        return guildId.matches("^\\d+$");
    }

    public boolean isDiscordChannelIdValid(String channelId) {
        if (!PLATFORM_DISCORD.equals(platform)) {
            return true;
        }
        if (channelId == null || channelId.trim().isEmpty()) {
            return false;
        }
        return channelId.matches("^\\d+$");
    }
}
