import {collapseDotKeys, createDiffObject, sortDict} from "../../utils/Utils";

test("collapseDotKeys", () => {
    const res = collapseDotKeys({
        "a.b.c": "val",
        "d.e.f": ["some"],
        "g": {a: 1},
        "x": "y"
    });
    expect(res.a.b.c).toStrictEqual("val");
    expect(res.d.e.f).toStrictEqual(["some"]);
    expect(res.g).toStrictEqual({a: 1});
    expect(res.x).toStrictEqual("y");
});

test("createDiff", () => {
    const res = createDiffObject(
        {a: "b", c: {d: "val", ign: "x"}, ign: [1, 2, 3]},
        {a: "x", c: {d: "changed"}, extra: [1]}
    );
    expect(res).toStrictEqual({a: "b", c: {d: "val"}});
});

test("createDiff nested array", () => {
    const data = {
        "entityid": "https://idp.test2.surfconext.nl",
        "allowedEntities": [
            {
                "name": "https://1234aaab.nl"
            },
            {
                "name": "https://serviceregistry.test2.surfconext.nl/simplesaml/module.php/saml/sp/metadata.php/default-sp"
            },
            {
                "name": "https://serviceregistry.test2.surfconext.nl/simplesaml/module.php/saml/sp/metadata.php/default-sp-2"
            },
            {
                "name": "http://mock-sp"
            },
            {
                "name": "https://oidc.test.client"
            }
        ],
        "metaDataFields": {
            "name:en": "OpenConext ServiceRegistry Mock",
        }
    }
    const nestedChangeRequest = {
        "allowedEntities": [
            {
                "name": "https://1234aaab.nl"
            },
            {
                "name": "https://serviceregistry.test2.surfconext.nl/simplesaml/module.php/saml/sp/metadata.php/default-sp"
            },
            {
                "name": "https://serviceregistry.test2.surfconext.nl/simplesaml/module.php/saml/sp/metadata.php/default-sp-2"
            },
            {
                "name": "https://profile.test2.surfconext.nl/authentication/metadata"
            },
            {
                "name": "http://mock-sp"
            },
            {
                "name": "https://oidc.test.client"
            }
        ]
    }
    const res = createDiffObject(
        data,
        nestedChangeRequest
    );
    expect(res).toStrictEqual({allowedEntities: data.allowedEntities});
});

test("createDiff", () => {
    const data = {a: "a", l1: [{name: "x"}, {name: "a"}], l2: [null, "", undefined]};
    sortDict(data);
    expect(data).toStrictEqual({a: "a", l1: [{name: "a"}, {name: "x"}], l2: [null, "", undefined]});
});

test("createDiffObjectArpNull", () => {
    const data = {
        "metaDataFields": {
            "name:en": "OIDC SP",
            "accessTokenValidity": 9999,
        },
        "arp": {
            "enabled": true,
            "attributes": {"urn:mace:dir:attribute-def:cn": [{"value": "*", "source": "idp", "motivation": ""}]}
        }
    }
    const nestedChangeRequest = {
        "metaDataFields": {"accessTokenValidity": 86400}, "arp": null
    }
    const res = createDiffObject(
        data,
        nestedChangeRequest
    );
    const expected = {"metaDataFields": {"accessTokenValidity": 9999}, "arp": {}}
    expect(res).toStrictEqual(expected);
})

