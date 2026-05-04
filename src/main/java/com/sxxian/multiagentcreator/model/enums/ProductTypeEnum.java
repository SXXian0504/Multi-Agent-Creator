package com.sxxian.multiagentcreator.model.enums;

import lombok.Getter;

import java.math.BigDecimal;

@Getter
public enum ProductTypeEnum {

    VIP_PERMANENT("VIP_PERMANENT", "永久会员", new BigDecimal("199.00"));

    private final String value;
    private final String description;
    private final BigDecimal price;

    ProductTypeEnum(String value, String description, BigDecimal price) {
        this.value = value;
        this.description = description;
        this.price = price;
    }

    public static ProductTypeEnum getByValue(String value) {
        if (value == null) {
            return null;
        }
        for (ProductTypeEnum typeEnum : values()) {
            if (typeEnum.getValue().equals(value)) {
                return typeEnum;
            }
        }
        return null;
    }
}