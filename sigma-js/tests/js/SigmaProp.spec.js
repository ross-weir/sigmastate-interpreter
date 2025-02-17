const { SigmaPropObj, ValueObj } = require("sigmastate-js/main");

let pointAsn1Hex = "02c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5";

describe("SigmaProp", () => {
    it("should implement fromPointHex", () => {
        let ge = SigmaPropObj.fromPointHex(pointAsn1Hex)
        expect(ge).not.toBeUndefined()

        let v = ValueObj.ofSigmaProp(pointAsn1Hex)
        expect(v.toHex())
            .toEqual("08"/* SigmaProp type id */ + "cd"/* ProveDlog.opCode */ + pointAsn1Hex)
    });
});