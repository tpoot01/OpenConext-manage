// Interpolation works as follows:
//
// Make a key with the translation and enclose the variable with {{}}
// ie "Hello {{name}}" Do not add any spaces around the variable name.
// Provide the values as: I18n.t("key", {name: "John Doe"})
import I18n from "i18n-js";

I18n.translations.en = {
    code: "EN",
    name: "English",
    select_locale: "Select English",

    header: {
        title: "Metadata Registry",
        links: {
            help_html: "<a href=\"https://wiki.surfnet.nl/pages/viewpage.action?pageId=35422637\" target=\"_blank\">Help</a>",
            logout: "Logout",
            exit: "Exit"
        },
        role: "Role"
    },

    navigation: {
        search: "Search",
        new: "New"
    },

    metadata: {
        saml20_sp: "Service Providers",
        saml20_idp: "Identity Providers",
        saml20_sp_single: "Service Provider",
        saml20_idp_single: "Identity Provider",
        searchPlaceHolder: "Search for metadata",

        tabs: {
            connection: "Connection",
            whitelist: "Whitelisting",
            metadata: "Metadata",
            arp: "ARP",
            manipulation: "manipulation",
            consent_disabling: "Consent Disabling",
            revisions: "Revisions"
        },
        notFound: "No Metadata found",
        entityId: "Entity ID",
        metaDataUrl: "Metadata URL",
        state: "State",
        prodaccepted: "Production",
        testaccepted: "Test",
        notes: "Notes",
        edit: "Edit",
        none: "",
        submit: "Submit",
        cancel: "Cancel",
        revisionnote: "Revision notes"

    },

    whitelisting: {
        confirmationAllowAll:"Are you sure you want to allow all {{type}} access to '{{name}}'? You will have to add {{type}} one-by-one to selectively deny them again.",
        confirmationAllowNone:"Are you sure you want to deny all {{type}} access to '{{name}}'? You will have to add {{type}} one-by-one or allow them all again.",
        placeholder: "Search, select and add {{type}} to the whitelist",
        allowAllProviders: "Allow all {{type}} access to {{name}}",
        title: "{{type}} Whitelist",
        description: "Add only those {{type}} which are allowed to access {{name}}.",
        allowedEntries: {
            blocked: "Blocked",
            status: "Status",
            entityid:"Entity ID",
            name: "Name"
        }
    },

    consentDisabling: {
        title: "Consent disabling",
        description: "Search and add Service Providers that will skip consent for '{{name}}'.",
        placeholder: "Search, select and add Service Providers to the consent-disabled-list",
        entries: {
            status: "Status",
            entityid:"Entity ID",
            name: "Name"
        }
    },

    manipulation : {
        description: "Documentation on attribute manipulations"
    },

    metaDataFields: {
        title: "Metadata of {{name}}",
        key: "Key",
        value: "Value",
        error: "Invalid {{format}}",
        placeholder: "Search and add metadata fields"
    },

    selectEntities: {

    },

    error_dialog: {
        title: "Unexpected error",
        body: "This is embarrassing; an unexpected error has occurred. It has been logged and reported. Please try again. Still doesn't work? Please click 'Help'.",
        ok: "Close"
    },

    confirmation_dialog: {
        title: "Please confirm",
        confirm: "Confirm",
        cancel: "Cancel",
        leavePage: "Do you really want to leave this page?",
        leavePageSub: "Changes that you made will not be saved.",
        stay: "Stay",
        leave: "Leave"
    },

    metadata_autocomplete: {
        entity_id: "Entity ID",
        name: "Name",
        state: "Production",
        no_results: "No results"
    },
};

export default I18n.translations.en;
