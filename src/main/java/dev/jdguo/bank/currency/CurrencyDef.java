package dev.jdguo.bank.currency;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * One currency in the registry. Commodity currencies name a vanilla item id.
 * Virtual currencies may be issued against a commodity basket.
 */
public final class CurrencyDef {
    private final String id;
    private final CurrencyKind kind;
    private final String itemId;
    private final String name;
    private final String nameEn;
    private final String color;
    private final BigInteger initialReserve;
    private final BigInteger initialLiability;
    private final BigDecimal alphaPermille;
    private final boolean issuable;
    private final String backing;
    private final BigDecimal parPerBacking;
    private final BigDecimal haircut;
    private final BigDecimal minReserveRatio;

    public CurrencyDef(String id, CurrencyKind kind, String itemId, String name, String nameEn, String color,
                       BigInteger initialReserve, BigInteger initialLiability, BigDecimal alphaPermille,
                       boolean issuable, String backing, BigDecimal parPerBacking, BigDecimal haircut,
                       BigDecimal minReserveRatio) {
        this.id = id;
        this.kind = kind;
        this.itemId = itemId;
        this.name = name;
        this.nameEn = nameEn;
        this.color = color;
        this.initialReserve = initialReserve;
        this.initialLiability = initialLiability == null ? initialReserve : initialLiability;
        this.alphaPermille = alphaPermille;
        this.issuable = issuable;
        this.backing = backing;
        this.parPerBacking = parPerBacking;
        this.haircut = haircut;
        this.minReserveRatio = minReserveRatio;
    }

    public static CurrencyDef orphan(String id) {
        return new CurrencyDef(id, CurrencyKind.VIRTUAL, "minecraft:paper", id, id, "white",
                BigInteger.ZERO, BigInteger.ZERO, new BigDecimal("1000"), false,
                null, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
    }

    public String id() { return id; }
    public CurrencyKind kind() { return kind; }
    public String itemId() { return itemId; }
    public String name() { return name; }
    public String nameEn() { return nameEn; }
    public String color() { return color; }
    public BigInteger initialReserve() { return initialReserve; }
    public BigInteger initialLiability() { return initialLiability; }
    public BigDecimal alphaPermille() { return alphaPermille; }
    public boolean issuable() { return issuable; }
    public String backing() { return backing; }
    public BigDecimal parPerBacking() { return parPerBacking; }
    public BigDecimal haircut() { return haircut; }
    public BigDecimal minReserveRatio() { return minReserveRatio; }
}
