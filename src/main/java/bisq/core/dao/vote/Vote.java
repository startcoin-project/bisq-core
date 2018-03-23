/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.core.dao.vote;

import bisq.core.dao.proposal.ProposalList;

import bisq.common.crypto.Encryption;
import bisq.common.proto.persistable.PersistablePayload;
import bisq.common.util.JsonExclude;

import io.bisq.generated.protobuffer.PB;

import org.bitcoinj.core.Utils;

import javax.crypto.SecretKey;

import java.util.Date;
import java.util.Optional;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;

@EqualsAndHashCode
@Slf4j
@Data
public class Vote implements PersistablePayload {

    private final ProposalList proposalList;
    private final String secretKeyAsHex;
    private final BlindVote blindVote;
    private final long date;
    private long stake;
    @Nullable
    private String revealTxId;

    // Used just for caching
    @JsonExclude
    @Nullable
    private transient SecretKey secretKey;

    Vote(ProposalList proposalList,
         String secretKeyAsHex,
         BlindVote blindVote,
         long stake) {
        this(proposalList,
                secretKeyAsHex,
                blindVote,
                new Date().getTime(),
                stake,
                null);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private Vote(ProposalList proposalList,
                 String secretKeyAsHex,
                 BlindVote blindVote,
                 long date,
                 long stake,
                 @Nullable String revealTxId) {
        this.proposalList = proposalList;
        this.secretKeyAsHex = secretKeyAsHex;
        this.blindVote = blindVote;
        this.date = date;
        this.stake = stake;
        this.revealTxId = revealTxId;
    }

    @Override
    public PB.Vote toProtoMessage() {
        final PB.Vote.Builder builder = PB.Vote.newBuilder()
                .setBlindVote(blindVote.getBuilder())
                .setProposalList(proposalList.getBuilder())
                .setSecretKeyAsHex(secretKeyAsHex)
                .setDate(date)
                .setStake(stake);
        Optional.ofNullable(revealTxId).ifPresent(builder::setRevealTxId);
        return builder.build();
    }

    public static Vote fromProto(PB.Vote proto) {
        return new Vote(ProposalList.fromProto(proto.getProposalList()),
                proto.getSecretKeyAsHex(),
                BlindVote.fromProto(proto.getBlindVote()),
                proto.getDate(),
                proto.getStake(),
                proto.getRevealTxId().isEmpty() ? null : proto.getRevealTxId());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public SecretKey getSecretKey() {
        if (secretKey == null)
            secretKey = Encryption.getSecretKeyFromBytes(Utils.HEX.decode(secretKeyAsHex));
        return secretKey;
    }

    public String getTxId() {
        return blindVote.getTxId();
    }
}
