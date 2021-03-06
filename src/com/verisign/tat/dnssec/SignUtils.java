/***************************** -*- Java -*- ********************************\
 *                                                                         *
 *   Copyright (c) 2009 VeriSign, Inc. All rights reserved.                *
 *                                                                         *
 * This software is provided solely in connection with the terms of the    *
 * license agreement.  Any other use without the prior express written     *
 * permission of VeriSign is completely prohibited.  The software and      *
 * documentation are "Commercial Items", as that term is defined in 48     *
 * C.F.R.  section 2.101, consisting of "Commercial Computer Software" and *
 * "Commercial Computer Software Documentation" as such terms are defined  *
 * in 48 C.F.R. section 252.227-7014(a)(5) and 48 C.F.R. section           *
 * 252.227-7014(a)(1), and used in 48 C.F.R. section 12.212 and 48 C.F.R.  *
 * section 227.7202, as applicable.  Pursuant to the above and other       *
 * relevant sections of the Code of Federal Regulations, as applicable,    *
 * VeriSign's publications, commercial computer software, and commercial   *
 * computer software documentation are distributed and licensed to United  *
 * States Government end users with only those rights as granted to all    *
 * other end users, according to the terms and conditions contained in the *
 * license agreement(s) that accompany the products and software           *
 * documentation.                                                          *
 *                                                                         *
\***************************************************************************/

package com.verisign.tat.dnssec;

import org.apache.log4j.Logger;

import org.xbill.DNS.DNSKEYRecord;
import org.xbill.DNS.DNSOutput;
import org.xbill.DNS.Name;
import org.xbill.DNS.RRSIGRecord;
import org.xbill.DNS.RRset;
import org.xbill.DNS.Record;
import org.xbill.DNS.utils.base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Serializable;

import java.security.SignatureException;
import java.security.interfaces.DSAParams;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;

/**
 * This class contains a bunch of utility methods that are generally useful in
 * signing and verifying rrsets.
 */
public class SignUtils {
    // private static final int DSA_SIGNATURE_LENGTH = 20;
    private static final int ASN1_INT = 0x02;
    private static final int ASN1_SEQ = 0x30;
    public static final int RR_NORMAL = 0;
    public static final int RR_DELEGATION = 1;
    public static final int RR_GLUE = 2;
    public static final int RR_INVALID = 3;
    private static Logger log = Logger.getLogger(SignUtils.class);

    /**
     * Generate from some basic information a prototype SIG RR containing
     * everything but the actual signature itself.
     * 
     * @param rrset
     *            the RRset being signed.
     * @param signer
     *            the name of the signing key
     * @param alg
     *            the algorithm of the signing key
     * @param keyid
     *            the keyid (or footprint) of the signing key
     * @param start
     *            the SIG inception time.
     * @param expire
     *            the SIG expiration time.
     * @param sig_ttl
     *            the TTL of the resulting SIG record.
     * @return a prototype signature based on the RRset and key information.
     */
    public static RRSIGRecord generatePreRRSIG(RRset rrset, Name signer,
            int alg, int keyid, Date start, Date expire, long sig_ttl) {
        return new RRSIGRecord(rrset.getName(), rrset.getDClass(), sig_ttl,
                rrset.getType(), alg, rrset.getTTL(), expire, start, keyid,
                signer, null);
    }

    /**
     * Generate from some basic information a prototype SIG RR containing
     * everything but the actual signature itself.
     * 
     * @param rrset
     *            the RRset being signed.
     * @param key
     *            the public KEY RR counterpart to the key being used to sign
     *            the RRset
     * @param start
     *            the SIG inception time.
     * @param expire
     *            the SIG expiration time.
     * @param sig_ttl
     *            the TTL of the resulting SIG record.
     * @return a prototype signature based on the RRset and key information.
     */
    public static RRSIGRecord generatePreRRSIG(RRset rrset, DNSKEYRecord key,
            Date start, Date expire, long sig_ttl) {
        return generatePreRRSIG(rrset, key.getName(), key.getAlgorithm(), key
                .getFootprint(), start, expire, sig_ttl);
    }

    /**
     * Generate from some basic information a prototype SIG RR containing
     * everything but the actual signature itself.
     * 
     * @param rec
     *            the DNS record being signed (forming an entire RRset).
     * @param key
     *            the public KEY RR counterpart to the key signing the record.
     * @param start
     *            the SIG inception time.
     * @param expire
     *            the SIG expiration time.
     * @param sig_ttl
     *            the TTL of the result SIG record.
     * @return a prototype signature based on the Record and key information.
     */
    public static RRSIGRecord generatePreRRSIG(Record rec, DNSKEYRecord key,
            Date start, Date expire, long sig_ttl) {
        return new RRSIGRecord(rec.getName(), rec.getDClass(), sig_ttl, rec
                .getType(), key.getAlgorithm(), rec.getTTL(), expire, start,
                key.getFootprint(), key.getName(), null);
    }

    /**
     * Generate the binary image of the prototype SIG RR.
     * 
     * @param presig
     *            the SIG RR prototype.
     * @return the RDATA portion of the prototype SIG record. This forms the
     *         first part of the data to be signed.
     */
    private static byte[] generatePreSigRdata(RRSIGRecord presig) {
        // Generate the binary image;
        DNSOutput image = new DNSOutput();

        // precalculate some things
        int start_time = (int) (presig.getTimeSigned().getTime() / 1000);
        int expire_time = (int) (presig.getExpire().getTime() / 1000);
        Name signer = presig.getSigner();

        // first write out the partial SIG record (this is the SIG RDATA
        // minus the actual signature.
        image.writeU16(presig.getTypeCovered());
        image.writeU8(presig.getAlgorithm());
        image.writeU8(presig.getLabels());
        image.writeU32((int) presig.getOrigTTL());
        image.writeU32(expire_time);
        image.writeU32(start_time);
        image.writeU16(presig.getFootprint());
        image.writeByteArray(signer.toWireCanonical());

        return image.toByteArray();
    }

    /**
     * Calculate the canonical wire line format of the RRset.
     * 
     * @param rrset
     *            the RRset to convert.
     * @param ttl
     *            the TTL to use when canonicalizing -- this is generally the
     *            TTL of the signature if there is a pre-existing signature. If
     *            not it is just the ttl of the rrset itself.
     * @param labels
     *            the labels field of the signature, or 0.
     * @return the canonical wire line format of the rrset. This is the second
     *         part of data to be signed.
     */
    @SuppressWarnings("rawtypes")
    public static byte[] generateCanonicalRRsetData(RRset rrset, long ttl,
            int labels) {
        DNSOutput image = new DNSOutput();

        if (ttl == 0) {
            ttl = rrset.getTTL();
        }

        Name n = rrset.getName();

        if (labels == 0) {
            labels = n.labels();
        } else {
            // correct for Name()'s conception of label count.
            labels++;
        }

        boolean wildcardName = false;

        if (n.labels() != labels) {
            n = n.wild(n.labels() - labels);
            wildcardName = true;
            log.trace("Detected wildcard expansion: " + rrset.getName()
                    + " changed to " + n);
        }

        // now convert the wire format records in the RRset into a
        // list of byte arrays.
        ArrayList<byte[]> canonical_rrs = new ArrayList<byte[]>();

        for (Iterator i = rrset.rrs(); i.hasNext();) {
            Record r = (Record) i.next();

            if ((r.getTTL() != ttl) || wildcardName) {
                // If necessary, we need to create a new record with a new ttl
                // or ownername.
                // In the TTL case, this avoids changing the ttl in the
                // response.
                r = Record.newRecord(n, r.getType(), r.getDClass(), ttl, r
                        .rdataToWireCanonical());
            }

            byte[] wire_fmt = r.toWireCanonical();
            canonical_rrs.add(wire_fmt);
        }

        // put the records into the correct ordering.
        // Calculate the offset where the RDATA begins (we have to skip
        // past the length byte)
        int offset = rrset.getName().toWireCanonical().length + 10;
        ByteArrayComparator bac = new ByteArrayComparator(offset, false);

        Collections.sort(canonical_rrs, bac);

        for (Iterator<byte[]> i = canonical_rrs.iterator(); i.hasNext();) {
            byte[] wire_fmt_rec = i.next();
            image.writeByteArray(wire_fmt_rec);
        }

        return image.toByteArray();
    }

    /**
     * Given an RRset and the prototype signature, generate the canonical data
     * that is to be signed.
     * 
     * @param rrset
     *            the RRset to be signed.
     * @param presig
     *            a prototype SIG RR created using the same RRset.
     * @return a block of data ready to be signed.
     */
    public static byte[] generateSigData(RRset rrset, RRSIGRecord presig)
            throws IOException {
        byte[] rrset_data = generateCanonicalRRsetData(rrset, presig
                .getOrigTTL(), presig.getLabels());

        return generateSigData(rrset_data, presig);
    }

    /**
     * Given an RRset and the prototype signature, generate the canonical data
     * that is to be signed.
     * 
     * @param rrset_data
     *            the RRset converted into canonical wire line format (as per
     *            the canonicalization rules in RFC 2535).
     * @param presig
     *            the prototype signature based on the same RRset represented in
     *            <code>rrset_data</code>.
     * @return a block of data ready to be signed.
     */
    public static byte[] generateSigData(byte[] rrset_data, RRSIGRecord presig)
            throws IOException {
        byte[] sig_rdata = generatePreSigRdata(presig);

        ByteArrayOutputStream image = new ByteArrayOutputStream(
                sig_rdata.length + rrset_data.length);

        image.write(sig_rdata);
        image.write(rrset_data);

        return image.toByteArray();
    }

    /**
     * Given the actual signature and the prototype signature, combine them and
     * return the fully formed RRSIGRecord.
     * 
     * @param signature
     *            the cryptographic signature, in DNSSEC format.
     * @param presig
     *            the prototype RRSIG RR to add the signature to.
     * @return the fully formed RRSIG RR.
     */
    public static RRSIGRecord generateRRSIG(byte[] signature, RRSIGRecord presig) {
        return new RRSIGRecord(presig.getName(), presig.getDClass(), presig
                .getTTL(), presig.getTypeCovered(), presig.getAlgorithm(),
                presig.getOrigTTL(), presig.getExpire(),
                presig.getTimeSigned(), presig.getFootprint(), presig
                        .getSigner(), signature);
    }

    /**
     * Converts from a RFC 2536 formatted DSA signature to a JCE (ASN.1)
     * formatted signature.
     * 
     * <p>
     * ASN.1 format = ASN1_SEQ . seq_length . ASN1_INT . Rlength . R . ANS1_INT
     * . Slength . S
     * </p>
     * 
     * The integers R and S may have a leading null byte to force the integer
     * positive.
     * 
     * @param signature
     *            the RFC 2536 formatted DSA signature.
     * @return The ASN.1 formatted DSA signature.
     * @throws SignatureException
     *             if there was something wrong with the RFC 2536 formatted
     *             signature.
     */
    public static byte[] convertDSASignature(byte[] signature)
            throws SignatureException {
        if (signature.length != 41) {
            throw new SignatureException(
                    "RFC 2536 signature not expected length.");
        }

        byte r_pad = 0;
        byte s_pad = 0;

        // handle initial null byte padding.
        if (signature[1] < 0) {
            r_pad++;
        }

        if (signature[21] < 0) {
            s_pad++;
        }

        // ASN.1 length = R length + S length + (2 + 2 + 2), where each 2
        // is for a ASN.1 type-length byte pair of which there are three
        // (SEQ, INT, INT).
        byte sig_length = (byte) (40 + r_pad + s_pad + 6);

        byte[] sig = new byte[sig_length];
        byte pos = 0;

        sig[pos++] = ASN1_SEQ;
        sig[pos++] = (byte) (sig_length - 2); // all but the SEQ type+length.
        sig[pos++] = ASN1_INT;
        sig[pos++] = (byte) (20 + r_pad);

        // copy the value of R, leaving a null byte if necessary
        if (r_pad == 1) {
            sig[pos++] = 0;
        }

        System.arraycopy(signature, 1, sig, pos, 20);
        pos += 20;

        sig[pos++] = ASN1_INT;
        sig[pos++] = (byte) (20 + s_pad);

        // copy the value of S, leaving a null byte if necessary
        if (s_pad == 1) {
            sig[pos++] = 0;
        }

        System.arraycopy(signature, 21, sig, pos, 20);

        return sig;
    }

    /**
     * Converts from a JCE (ASN.1) formatted DSA signature to a RFC 2536
     * compliant signature.
     * 
     * <p>
     * rfc2536 format = T . R . S
     * </p>
     * 
     * where T is a number between 0 and 8, which is based on the DSA key
     * length, and R & S are formatted to be exactly 20 bytes each (no leading
     * null bytes).
     * 
     * @param params
     *            the DSA parameters associated with the DSA key used to
     *            generate the signature.
     * @param signature
     *            the ASN.1 formatted DSA signature.
     * @return a RFC 2536 formatted DSA signature.
     * @throws SignatureException
     *             if something is wrong with the ASN.1 format.
     */
    public static byte[] convertDSASignature(DSAParams params, byte[] signature)
            throws SignatureException {
        if ((signature[0] != ASN1_SEQ) || (signature[2] != ASN1_INT)) {
            throw new SignatureException(
                    "Invalid ASN.1 signature format: expected SEQ, INT");
        }

        byte r_pad = (byte) (signature[3] - 20);

        if (signature[24 + r_pad] != ASN1_INT) {
            throw new SignatureException(
                    "Invalid ASN.1 signature format: expected SEQ, INT, INT");
        }

        log.trace("(start) ASN.1 DSA Sig:\n" + base64.toString(signature));

        byte s_pad = (byte) (signature[25 + r_pad] - 20);

        byte[] sig = new byte[41]; // all rfc2536 signatures are 41 bytes.

        // Calculate T:
        sig[0] = (byte) ((params.getP().bitLength() - 512) / 64);

        // copy R value
        if (r_pad >= 0) {
            System.arraycopy(signature, 4 + r_pad, sig, 1, 20);
        } else {
            // R is shorter than 20 bytes, so right justify the number
            // (r_pad is negative here, remember?).
            Arrays.fill(sig, 1, 1 - r_pad, (byte) 0);
            System.arraycopy(signature, 4, sig, 1 - r_pad, 20 + r_pad);
        }

        // copy S value
        if (s_pad >= 0) {
            System.arraycopy(signature, 26 + r_pad + s_pad, sig, 21, 20);
        } else {
            // S is shorter than 20 bytes, so right justify the number
            // (s_pad is negative here).
            Arrays.fill(sig, 21, 21 - s_pad, (byte) 0);
            System
                    .arraycopy(signature, 26 + r_pad, sig, 21 - s_pad,
                            20 + s_pad);
        }

        if ((r_pad < 0) || (s_pad < 0)) {
            log
                    .trace("(finish ***) RFC 2536 DSA Sig:\n"
                            + base64.toString(sig));
        } else {
            log.trace("(finish) RFC 2536 DSA Sig:\n" + base64.toString(sig));
        }

        return sig;
    }

    /**
     * This class implements a basic comparator for byte arrays. It is primarily
     * useful for comparing RDATA portions of DNS records in doing DNSSEC
     * canonical ordering.
     */
    public static class ByteArrayComparator implements Comparator<byte[]>, Serializable {
        private static final long serialVersionUID = 1L;
        private int mOffset = 0;
        private boolean mDebug = false;

        public ByteArrayComparator() {
        }

        public ByteArrayComparator(int offset, boolean debug) {
            mOffset = offset;
            mDebug = debug;
        }

        public int compare(byte[] b1, byte[] b2) throws ClassCastException {
            for (int i = mOffset; (i < b1.length) && (i < b2.length); i++) {
                if (b1[i] != b2[i]) {
                    if (mDebug) {
                        System.out
                                .println("offset " + i + " differs (this is "
                                        + (i - mOffset)
                                        + " bytes in from our offset.)");
                    }

                    return (b1[i] & 0xFF) - (b2[i] & 0xFF);
                }
            }

            return b1.length - b2.length;
        }
    }
}
