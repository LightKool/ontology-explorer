package com.github.ontio.model.dto.ranking;

import lombok.Getter;
import lombok.Setter;

/**
 * @author LiuQi
 */
@Getter
@Setter
public class TokenRankingDto extends BaseRankingDto {
	
	private String contractHash;
	
	private String tokenName;
	
}