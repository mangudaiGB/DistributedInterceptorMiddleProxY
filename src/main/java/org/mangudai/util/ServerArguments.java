/*
 * Copyright (c) 2017. TechHive Software Labs, Inc - All Rights Reserved.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 * Proprietary and confidential
 */

package org.mangudai.util;

import com.beust.jcommander.Parameter;

/**
 * Created by neo on 24/03/17.
 */
public class ServerArguments {
    @Parameter(names = {"-p", "--port"}, description = "Port on which the server has to run.")
    public Integer port = 9999;

    @Parameter(names = {"-i", "--input"}, description = "File which is supposed to be replaced.", required = true)
    public String inputFile = "";

    @Parameter(names = {"-o", "--output"}, description = "Your file path.", required = true)
    public String outputFile = "";
}
