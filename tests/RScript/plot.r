rm(test)
rm(training)
training <- read.delim("~/lab_repos/VariationAnalysis/tests/1464722138875/training", header=TRUE)
View(training)
test <- read.delim("~/lab_repos/VariationAnalysis/tests/1464722138875/test", header=TRUE)
View(test)
auc(roc(predictions=training$V2,labels=factor(training$V1)))
auc(roc(predictions=test$V2,labels=factor(test$V1)))
plot(roc(predictions=training$Probability,labels=factor(training$mutatedLabel)),col="red")
par(new=TRUE)
plot(roc(predictions=test$Probability,labels=factor(test$mutatedLabel)),col="green")