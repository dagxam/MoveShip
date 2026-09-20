Также убери из MoveShipPlugin.java создание ShipMovementListener через ProtocolLib — теперь он не нужен, просто регистрируй как обычный listener:

Java

// В onEnable() замени:
// new ShipMovementListener(this);  // УБЕРИ ЭТО

// На:
getServer().getPluginManager().registerEvents(new ShipMovementListener(this), this);
И убери из pom.xml зависимость ProtocolLib если больше не используется нигде:

XML

<!-- Если ProtocolLib больше не нужен — убери эту зависимость -->
<dependency>
    <groupId>com.comphenix.protocol</groupId>
    <artifactId>ProtocolLib</artifactId>
    <version>5.3.0</version>
    <scope>provided</scope>
</dependency>
И из plugin.yml:

YAML

# depend убери если ProtocolLib не нужен
depend: []




