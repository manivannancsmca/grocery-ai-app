package com.grocery.ai.knowledge;

/** What a help-center document is about (the brief's categories A–F). */
public enum KnowledgeCategory {
    /** How to use the app: registration, login, the account, general FAQs. */
    APP_GUIDE,
    /** General product guides: storage, usage, product FAQs. Never prices or stock (those come from the catalog). */
    PRODUCT_GUIDE,
    /** Ordering, delivery, payment, cancellation, return and refund policies (in words; the numbers admins set
     * live in platform settings and come from the getStoreRules tool). */
    POLICIES,
    /** Vendor registration, onboarding, products, orders and payouts. */
    VENDOR,
    /** The delivery process, delivery partner guidelines and status explanations. */
    DELIVERY,
    /** Customer support: troubleshooting and how to reach the support team. */
    SUPPORT
}
